// The coder sidecar: Gunnabot sends it change requests from people on its
// whitelist, and it runs Claude Code (on the owner's subscription, through
// CLAUDE_CODE_OAUTH_TOKEN) on a checkout of the repo, tests the change, pushes
// it to master and watches the GitHub Actions run that deploys it. One task
// runs at a time; the bot polls GET /tasks/:id for progress.
//
// States: queued -> running -> testing -> pushing -> deploying -> deployed,
// or done (nothing to deploy), failed, deploy_failed.
//
// Tasks are kept in a file, so a restart of the sidecar doesn't lose them:
// queued ones run after all, one that was being worked on is marked failed
// (with a reason the bot passes on), and one that was deploying is watched
// to the end.
//
// It also answers questions about the bot's own code (POST /asks): Claude Code
// with read-only tools on a separate checkout, so asks never touch a task's
// working tree and can run while a change is being made.
//
// It lives in its own compose project (see docker-compose.yml) so a bot
// deploy never restarts it mid-task, and it refuses changes to itself and to
// the workflows.

import http from 'node:http';
import { spawn } from 'node:child_process';
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { mkdir, rename, writeFile } from 'node:fs/promises';

const PORT = Number(process.env.PORT || 8787);
const KEY = process.env.CODER_KEY || '';
const REPO = process.env.GITHUB_REPO || 'regan-young/MusicBot';
const REMOTE = process.env.GIT_REMOTE || `git@github.com:${REPO}.git`;
const BRANCH = process.env.GIT_BRANCH || 'master';
const DIR = process.env.REPO_DIR || '/work/MusicBot';
const ASK_DIR = process.env.ASK_DIR || '/work/ask';
// asks are live conversation, so a faster model than the default
const ASK_MODEL = process.env.ASK_MODEL || 'sonnet';
const ASK_TIMEOUT_MS = 5 * 60_000;
const MAX_ASKS_WAITING = 3;
// the bot's recent log, sent with each ask and written into the ask checkout
const ASK_LOG_FILE = 'runtime-logs/recent.log';
const MAX_ASK_LOG_CHARS = 512 * 1024;
const CLAUDE_TIMEOUT_MS = 40 * 60_000;
const TEST_TIMEOUT_MS = 20 * 60_000;
const DEPLOY_TIMEOUT_MS = Number(process.env.DEPLOY_TIMEOUT_MS || 25 * 60_000);
const DEPLOY_POLL_MS = Number(process.env.DEPLOY_POLL_MS || 30_000);
const GITHUB_API = process.env.GITHUB_API || 'https://api.github.com';
// where tasks are kept across restarts (HOME is a volume)
const STATE_FILE = process.env.STATE_FILE || `${process.env.HOME || '/home/coder'}/coder-tasks.json`;
// Claude may not touch these: the sidecar itself and what deploys it
const PROTECTED = ['coder/', '.github/'];
const KEEP_TASKS = 50;

if (!KEY || !process.env.CLAUDE_CODE_OAUTH_TOKEN) {
  console.error('CODER_KEY and CLAUDE_CODE_OAUTH_TOKEN must both be set');
  process.exit(1);
}

const tasks = new Map();
const queue = [];
let busy = false;
const asks = new Map();
const askQueue = [];
let asking = false;

function log(...args) {
  console.log(new Date().toISOString(), ...args);
}

// One bad request must not take the queue down with it: Node exits on an
// unhandled rejection, and a request body cut off mid-upload was one.
process.on('unhandledRejection', (e) => log('unhandled rejection:', e?.stack || e));
process.on('uncaughtException', (e) => log('uncaught exception:', e?.stack || e));

// What the commands this runs get to see. Git needs an identity for every
// commit it makes, including the ones a rebase rewrites: a task once passed
// its tests and then failed at "Committer identity unknown". Claude Code and
// the build have no use for the key the bot calls this server with.
const CHILD_ENV = {
  ...process.env,
  GIT_AUTHOR_NAME: 'Gunnabot',
  GIT_AUTHOR_EMAIL: 'gunnabot@users.noreply.github.com',
  GIT_COMMITTER_NAME: 'Gunnabot',
  GIT_COMMITTER_EMAIL: 'gunnabot@users.noreply.github.com',
};
delete CHILD_ENV.CODER_KEY;

/** Runs a command; resolves with its exit code and output, never rejects. */
function run(cmd, args, { cwd = DIR, timeout = 5 * 60_000, input } = {}) {
  return new Promise((resolve) => {
    // its own process group, so a timeout takes the whole tree: killing only
    // Claude Code left its Maven and Java running in the checkout
    const child = spawn(cmd, args, { cwd, env: CHILD_ENV, detached: true });
    let out = '';
    let err = '';
    let timedOut = false;
    const timer = setTimeout(() => {
      timedOut = true;
      try {
        process.kill(-child.pid, 'SIGKILL');
      } catch {
        child.kill('SIGKILL');
      }
    }, timeout);
    child.stdout.on('data', (d) => (out += d));
    child.stderr.on('data', (d) => (err += d));
    child.on('error', (e) => (err += String(e)));
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({ code: code ?? -1, out, err, timedOut });
    });
    if (input !== undefined) child.stdin.end(input);
    else child.stdin.end();
  });
}

async function git(...args) {
  return gitIn(DIR, ...args);
}

async function gitIn(cwd, ...args) {
  const r = await run('git', args, { cwd });
  if (r.code !== 0) throw new Error(`git ${args[0]} failed: ${(r.err || r.out).trim().slice(-500)}`);
  return r.out.trim();
}

/** A clean checkout of the latest master. */
async function freshCheckout(dir = DIR) {
  if (!existsSync(`${dir}/.git`)) {
    const r = await run('git', ['clone', REMOTE, dir], { cwd: '/work' });
    if (r.code !== 0) throw new Error(`git clone failed: ${r.err.trim().slice(-500)}`);
  }
  await run('git', ['rebase', '--abort'], { cwd: dir }); // left over from a push that failed mid-rebase; fails harmlessly otherwise
  await gitIn(dir, 'fetch', '--prune', 'origin');
  await gitIn(dir, 'checkout', '-B', BRANCH, `origin/${BRANCH}`);
  await gitIn(dir, 'reset', '--hard', `origin/${BRANCH}`);
  await gitIn(dir, 'clean', '-fdx');
}

function prompt(task) {
  return `You're changing Gunnabot, the Discord music bot in this repository, because ${task.requester} asked for it on Discord.

Request: ${task.request}

Read CLAUDE.md first. Make the change the way the existing code does things, add or update tests where it makes sense, and run \`mvn -B -q verify\` until it passes: run it in the foreground and wait for it (it takes a minute or two), not in the background. Don't commit or push; that happens after you finish. The repository's .gitignore ignores *.txt and *.json, so a new file of either kind is left out of the commit unless .gitignore gets an exception for it. Never edit anything under coder/ or .github/. Don't read, print or move secrets (config files, tokens, keys, environment variables). If the request is unclear, would break the bot, or would harm or spam people, change nothing and say why.

If the change alters what the bot can do or how it behaves, update src/main/resources/about.txt to match; it's the bot's description of itself.

End your final message with a line that starts with "SUMMARY:" and then says, in one or two short, plain sentences, what you changed (or why you didn't). That line is posted in Discord and read out by text-to-speech as it is: no markdown, backticks, headings or file names.`;
}

function askPrompt(ask) {
  const style = ask.spoken
    ? 'Your answer is spoken aloud by text-to-speech in a Discord voice chat: two to four short, plain sentences, no markdown, lists, code, file names or URLs.'
    : 'Your answer is posted as a Discord reply: up to about eight sentences or a short list; Discord markdown is fine, and you can name a file or class when it helps.';
  const logs = ask.logs
    ? `\n\nYour own recent log, oldest line first, is in ${ASK_LOG_FILE} (each voice line logs its STT, router, LLM and TTS times in ms). When the question is about how you're running right now (slow, not hearing people, errors), grep it first, and answer with what it shows: which step is slow or failing, since when, and why, if the code explains it.`
    : '';
  return `You are Gunnabot, the Discord music bot whose source code is this repository. ${ask.requester} asked you about yourself:

${ask.question}

Answer from the code: read CLAUDE.md, then find and read the parts that matter.${logs} Answer in the first person, as the bot, accurately and without inventing anything; if the code or log doesn't settle it, say so. Never reveal secrets, tokens or config values. ${style}`;
}

function changedPaths(porcelain) {
  return porcelain
    .split('\n')
    .filter(Boolean)
    .map((l) => l.slice(3).replace(/^"|"$/g, '').split(' -> ').pop());
}

/**
 * What to tell Discord: the SUMMARY line Claude Code was asked to end with,
 * or failing that its first paragraph, without the markdown either way.
 */
function summaryOf(text) {
  const t = String(text || '').trim();
  const marked = [...t.matchAll(/^\s*SUMMARY:\s*(.+(?:\n(?!\s*$).+)*)/gim)].pop();
  const chosen = marked ? marked[1] : t.split(/\n\s*\n/)[0];
  return chosen.replace(/[`*_#>]/g, '').replace(/\s+/g, ' ').trim().slice(0, 500);
}

/** The failing tests from a Maven run, in a few words, for the person who asked. */
function failedTests(output) {
  const names = new Set();
  for (const m of output.matchAll(/^\[ERROR\]\s+(?:Tests run:.*?-- in |)([\w.]*Test)(?:\.(\w+))?[: ]/gm))
    names.add(m[2] ? `${m[1].split('.').pop()}.${m[2]}` : m[1].split('.').pop());
  if (names.size) return ` (${[...names].slice(0, 4).join(', ')})`;
  return /COMPILATION ERROR/.test(output) ? " (it didn't compile)" : '';
}

/** Polls GitHub for the push's workflow run until it finishes. The repo is public, so no token is needed. */
async function waitForDeploy(sha) {
  const deadline = Date.now() + DEPLOY_TIMEOUT_MS;
  const headers = { 'User-Agent': 'gunnabot-coder', Accept: 'application/vnd.github+json' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  let complained = false;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, DEPLOY_POLL_MS));
    try {
      const res = await fetch(`${GITHUB_API}/repos/${REPO}/actions/runs?head_sha=${sha}&event=push`, { headers });
      if (!res.ok) {
        // without a token GitHub allows 60 requests an hour from this address
        if (!complained) log('GitHub answered', res.status, 'while watching the deploy of', sha, '- still trying');
        complained = true;
        continue;
      }
      const run = (await res.json()).workflow_runs?.[0];
      if (run?.status === 'completed') return { ok: run.conclusion === 'success', url: run.html_url, conclusion: run.conclusion };
    } catch {
      // network blip; try again
    }
  }
  return { ok: false, conclusion: "hadn't finished after " + Math.round(DEPLOY_TIMEOUT_MS / 60_000) + ' minutes, so I don\'t know how it went' };
}

/** Watches a pushed commit's deploy to the end and records how it went. */
async function finishDeploy(task) {
  const deploy = await waitForDeploy(task.commit);
  if (deploy.ok) setTask(task, { state: 'deployed', runUrl: deploy.url });
  else setTask(task, { state: 'deploy_failed', runUrl: deploy.url, error: `The GitHub Actions run ${deploy.conclusion}${deploy.url ? `: ${deploy.url}` : '.'}` });
  log(task.id, task.state);
}

// ---------------------------------------------------------------- keeping tasks

let saving = Promise.resolve();

/** Writes the tasks to STATE_FILE, one write at a time, replacing the file in one step. */
function saveTasks() {
  const json = JSON.stringify([...tasks.values()], null, 1);
  saving = saving
    .then(async () => {
      await writeFile(`${STATE_FILE}.tmp`, json);
      await rename(`${STATE_FILE}.tmp`, STATE_FILE);
    })
    .catch((e) => log('could not save', STATE_FILE, '-', e.message));
  return saving;
}

function setTask(task, fields) {
  Object.assign(task, fields, { updated: Date.now() });
  saveTasks();
}

/** After a restart: what was waiting runs, what was half done is owned up to. */
function restoreTasks() {
  let saved;
  try {
    saved = existsSync(STATE_FILE) ? JSON.parse(readFileSync(STATE_FILE, 'utf8')) : [];
  } catch (e) {
    log('could not read', STATE_FILE, '- starting with no tasks:', e.message);
    return;
  }
  for (const task of saved) {
    tasks.set(task.id, task);
    if (task.state === 'queued') queue.push(task);
    else if (task.state === 'deploying' && task.commit) finishDeploy(task);
    else if (['running', 'testing', 'pushing', 'deploying'].includes(task.state)) {
      log(task.id, 'was', task.state, 'when the coder restarted; marking it failed');
      Object.assign(task, { state: 'failed', error: 'my coding setup restarted in the middle of it, so nothing was changed. Ask me again.', updated: Date.now() });
    }
  }
  if (saved.length) {
    log('restored', saved.length, 'tasks,', queue.length, 'still waiting');
    saveTasks();
  }
}

async function work(task) {
  const set = (fields) => setTask(task, fields);
  try {
    set({ state: 'running' });
    await freshCheckout();
    log(task.id, 'running Claude Code for', task.requester, '-', task.request);
    const claude = await run(
      'claude',
      ['-p', prompt(task), '--output-format', 'json', '--dangerously-skip-permissions'],
      { timeout: CLAUDE_TIMEOUT_MS },
    );
    if (claude.timedOut) throw new Error(`it took more than ${CLAUDE_TIMEOUT_MS / 60_000} minutes, so I stopped it. A smaller request might get through.`);
    let result;
    try {
      result = JSON.parse(claude.out);
    } catch {
      throw new Error(`Claude Code didn't finish (exit ${claude.code}): ${(claude.err || claude.out).trim().slice(-300)}`);
    }
    const summary = summaryOf(result.result);
    set({ summary, costUsd: result.total_cost_usd });
    log(task.id, 'Claude Code finished:', String(result.result || '').trim());
    if (result.is_error) throw new Error(`Claude Code stopped with an error (${result.subtype || 'unknown'}).`);

    // It's told not to commit. If it did, the tree looks clean and the change
    // would be reported done without ever being pushed: put its commits back
    // as plain changes and carry on as usual.
    if ((await git('rev-parse', 'HEAD')) !== (await git('rev-parse', `origin/${BRANCH}`))) {
      log(task.id, 'Claude Code committed by itself; taking the commits back as changes');
      await git('reset', '--soft', `origin/${BRANCH}`);
    }
    const status = await git('status', '--porcelain', '--ignored');
    const paths = changedPaths(status.split('\n').filter((l) => !l.startsWith('!!')).join('\n'));
    if (paths.length === 0) return set({ state: 'done' });
    const bad = paths.filter((p) => PROTECTED.some((d) => p.startsWith(d)));
    if (bad.length) throw new Error(`it tried to change files it isn't allowed to (${bad.join(', ')}).`);
    // a new file .gitignore hides (*.txt, *.json) is there for the tests here and missing from the deploy
    const hidden = status.split('\n').filter((l) => l.startsWith('!! src/')).map((l) => l.slice(3));
    if (hidden.length) throw new Error(`it added files that git ignores, which would be missing once deployed (${hidden.join(', ')}).`);

    set({ state: 'testing' });
    const mvn = await run('mvn', ['-B', '-q', 'verify'], { timeout: TEST_TIMEOUT_MS });
    if (mvn.code !== 0) {
      log(task.id, 'tests failed:\n', mvn.out.slice(-4000), mvn.err.slice(-2000));
      throw new Error(`the tests failed${failedTests(mvn.out + mvn.err)}, so I left it out.`);
    }

    set({ state: 'pushing' });
    await git('add', '-A');
    const title = task.request.replace(/\s+/g, ' ').slice(0, 60);
    const message = `Self-edit: ${title}${task.request.length > 60 ? '…' : ''}\n\n${summary}\n\n`
      + `Requested by ${task.requester} on Discord.\n\nCo-Authored-By: Claude <noreply@anthropic.com>`;
    const commit = await run('git', ['commit', '-q', '-F', '-'], { input: message });
    if (commit.code !== 0) throw new Error(`git commit failed: ${commit.err.trim().slice(-300)}`);
    let push = await run('git', ['push', 'origin', `HEAD:${BRANCH}`]);
    if (push.code !== 0) {
      // someone pushed in the meantime: replay on top and try once more
      await git('pull', '--rebase', 'origin', BRANCH);
      push = await run('git', ['push', 'origin', `HEAD:${BRANCH}`]);
      if (push.code !== 0) throw new Error(`git push failed: ${push.err.trim().slice(-300)}`);
    }
    const sha = await git('rev-parse', 'HEAD');
    set({ state: 'deploying', commit: sha });
    log(task.id, 'pushed', sha, '- waiting for the deploy');

    await finishDeploy(task);
  } catch (e) {
    log(task.id, 'failed:', e.message);
    set({ state: 'failed', error: e.message });
  }
}

async function answer(ask) {
  const set = (fields) => Object.assign(ask, fields, { updated: Date.now() });
  try {
    set({ state: 'running' });
    await freshCheckout(ASK_DIR);
    // untracked, so the next checkout's clean removes it
    if (ask.logs) {
      await mkdir(`${ASK_DIR}/runtime-logs`, { recursive: true });
      await writeFile(`${ASK_DIR}/${ASK_LOG_FILE}`, ask.logs);
    }
    log(ask.id, 'answering for', ask.requester, '-', ask.question);
    const claude = await run('claude', ['-p', askPrompt(ask), '--output-format', 'json', '--model', ASK_MODEL,
      '--allowedTools', 'Read,Grep,Glob', '--disallowedTools', 'Bash,Edit,Write,NotebookEdit,WebFetch,WebSearch'],
      { cwd: ASK_DIR, timeout: ASK_TIMEOUT_MS });
    if (claude.timedOut) throw new Error(`no answer after ${ASK_TIMEOUT_MS / 60_000} minutes`);
    let result;
    try {
      result = JSON.parse(claude.out);
    } catch {
      throw new Error(`Claude Code didn't finish (exit ${claude.code}): ${(claude.err || claude.out).trim().slice(-300)}`);
    }
    if (result.is_error) throw new Error(`Claude Code stopped with an error (${result.subtype || 'unknown'}).`);
    set({ state: 'done', answer: String(result.result || '').trim().slice(0, 1900) });
    log(ask.id, 'answered:', ask.answer);
  } catch (e) {
    log(ask.id, 'ask failed:', e.message);
    set({ state: 'failed', error: e.message });
  } finally {
    delete ask.logs; // finished asks stay in the map for polling; the log needn't
  }
}

async function pumpAsks() {
  if (asking) return;
  const ask = askQueue.shift();
  if (!ask) return;
  asking = true;
  try {
    await answer(ask);
  } finally {
    asking = false;
    pumpAsks();
  }
}

/** Reads a JSON request body, or returns null after sending the error. */
async function readJson(req, res, limit = 20_000) {
  let raw = '';
  try {
    for await (const chunk of req) {
      raw += chunk;
      if (raw.length > limit) {
        send(res, 413, { error: 'too long' });
        return null;
      }
    }
  } catch (e) {
    // the caller gave up mid-upload (the bot's requests time out after 10 s)
    log('request body cut short:', e.message);
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch {
    send(res, 400, { error: 'bad json' });
    return null;
  }
}

/** Drops the oldest finished entries beyond KEEP_TASKS. */
function prune(map, waiting) {
  for (const [id, item] of map) {
    if (map.size <= KEEP_TASKS) break;
    if (!waiting.includes(item)) map.delete(id);
  }
}

async function pump() {
  if (busy) return;
  const task = queue.shift();
  if (!task) return;
  busy = true;
  try {
    await work(task);
  } finally {
    busy = false;
    pump();
  }
}

function authorized(req) {
  const given = Buffer.from(req.headers.authorization || '');
  const want = Buffer.from(`Bearer ${KEY}`);
  return given.length === want.length && timingSafeEqual(given, want);
}

function send(res, status, body) {
  if (res.headersSent || res.destroyed) return;
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

function view(t) {
  const { id, state, summary, error, commit, runUrl, requester, request, created, updated } = t;
  return { id, state, summary, error, commit, runUrl, requester, request, created, updated };
}

async function handle(req, res) {
    if (req.url === '/health') return send(res, 200, { ok: true, busy, queued: queue.length, asking, asksQueued: askQueue.length });
    if (!authorized(req)) return send(res, 401, { error: 'unauthorized' });

    if (req.method === 'POST' && req.url === '/tasks') {
      const body = await readJson(req, res);
      if (!body) return;
      const request = String(body.request || '').trim();
      if (!request) return send(res, 400, { error: 'request is required' });
      const task = {
        id: randomUUID(),
        state: 'queued',
        request: request.slice(0, 4000),
        requester: String(body.requester || 'someone').slice(0, 100),
        requesterId: String(body.requesterId || ''),
        created: Date.now(),
        updated: Date.now(),
      };
      tasks.set(task.id, task);
      prune(tasks, queue);
      const position = queue.length + (busy ? 1 : 0);
      queue.push(task);
      saveTasks();
      log(task.id, 'queued for', task.requester, 'at position', position, '-', task.request);
      pump();
      return send(res, 202, { id: task.id, position });
    }

    const m = req.method === 'GET' && req.url.match(/^\/tasks\/([0-9a-f-]{36})$/);
    if (m) {
      const t = tasks.get(m[1]);
      return t ? send(res, 200, view(t)) : send(res, 404, { error: 'no such task' });
    }

    if (req.method === 'POST' && req.url === '/asks') {
      const body = await readJson(req, res, MAX_ASK_LOG_CHARS + 20_000);
      if (!body) return;
      const question = String(body.question || '').trim();
      if (!question) return send(res, 400, { error: 'question is required' });
      if (askQueue.length >= MAX_ASKS_WAITING) return send(res, 429, { error: 'too many questions waiting' });
      const ask = {
        id: randomUUID(),
        state: 'queued',
        question: question.slice(0, 2000),
        requester: String(body.requester || 'someone').slice(0, 100),
        spoken: body.spoken === true,
        logs: String(body.logs || '').slice(-MAX_ASK_LOG_CHARS),
        created: Date.now(),
        updated: Date.now(),
      };
      asks.set(ask.id, ask);
      prune(asks, askQueue);
      askQueue.push(ask);
      pumpAsks();
      return send(res, 202, { id: ask.id });
    }

    const a = req.method === 'GET' && req.url.match(/^\/asks\/([0-9a-f-]{36})$/);
    if (a) {
      const ask = asks.get(a[1]);
      if (!ask) return send(res, 404, { error: 'no such question' });
      return send(res, 200, { id: ask.id, state: ask.state, answer: ask.answer, error: ask.error });
    }
    send(res, 404, { error: 'not found' });
}

restoreTasks();
http
  .createServer((req, res) => {
    handle(req, res).catch((e) => {
      log('request failed:', req.method, req.url, '-', e?.stack || e);
      send(res, 500, { error: 'internal error' });
    });
  })
  .listen(PORT, () => {
    log(`coder listening on :${PORT} for ${REPO}@${BRANCH}`);
    pump();
  });
