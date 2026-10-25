// The coder sidecar: Gunnabot sends it change requests from people on its
// whitelist, and it runs Claude Code (on the owner's subscription, through
// CLAUDE_CODE_OAUTH_TOKEN) on a checkout of the repo, tests the change, pushes
// it to master and watches the GitHub Actions run that deploys it. One task
// runs at a time; the bot polls GET /tasks/:id for progress.
//
// States: queued -> running -> testing -> pushing -> deploying -> deployed,
// or done (nothing to deploy), failed, deploy_failed.
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
import { existsSync } from 'node:fs';

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
const CLAUDE_TIMEOUT_MS = 40 * 60_000;
const TEST_TIMEOUT_MS = 20 * 60_000;
const DEPLOY_TIMEOUT_MS = 25 * 60_000;
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

/** Runs a command; resolves with its exit code and output, never rejects. */
function run(cmd, args, { cwd = DIR, timeout = 5 * 60_000, input } = {}) {
  return new Promise((resolve) => {
    const child = spawn(cmd, args, { cwd, env: process.env });
    let out = '';
    let err = '';
    const timer = setTimeout(() => child.kill('SIGKILL'), timeout);
    child.stdout.on('data', (d) => (out += d));
    child.stderr.on('data', (d) => (err += d));
    child.on('error', (e) => (err += String(e)));
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve({ code: code ?? -1, out, err });
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

Read CLAUDE.md first. Make the change the way the existing code does things, add or update tests where it makes sense, and run \`mvn -B verify\` until it passes. Don't commit or push; that happens after you finish. Never edit anything under coder/ or .github/. Don't read, print or move secrets (config files, tokens, keys, environment variables). If the request is unclear, would break the bot, or would harm or spam people, change nothing and say why.

If the change alters what the bot can do or how it behaves, update src/main/resources/about.txt to match; it's the bot's description of itself.

Your final message is posted in Discord as it is, so make it one or two short, plain sentences saying what you changed (or why you didn't). No markdown, headings or file names.`;
}

function askPrompt(ask) {
  const style = ask.spoken
    ? 'Your answer is spoken aloud by text-to-speech in a Discord voice chat: two to four short, plain sentences, no markdown, lists, code, file names or URLs.'
    : 'Your answer is posted as a Discord reply: up to about eight sentences or a short list; Discord markdown is fine, and you can name a file or class when it helps.';
  return `You are Gunnabot, the Discord music bot whose source code is this repository. ${ask.requester} asked you about yourself:

${ask.question}

Answer from the code: read CLAUDE.md, then find and read the parts that matter. Answer in the first person, as the bot, accurately and without inventing anything; if the code doesn't settle it, say so. Never reveal secrets, tokens or config values. ${style}`;
}

function changedPaths(porcelain) {
  return porcelain
    .split('\n')
    .filter(Boolean)
    .map((l) => l.slice(3).replace(/^"|"$/g, '').split(' -> ').pop());
}

/** Polls GitHub for the push's workflow run until it finishes. The repo is public, so no token is needed. */
async function waitForDeploy(sha) {
  const deadline = Date.now() + DEPLOY_TIMEOUT_MS;
  const headers = { 'User-Agent': 'gunnabot-coder', Accept: 'application/vnd.github+json' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 30_000));
    try {
      const res = await fetch(`https://api.github.com/repos/${REPO}/actions/runs?head_sha=${sha}&event=push`, { headers });
      if (!res.ok) continue;
      const run = (await res.json()).workflow_runs?.[0];
      if (run?.status === 'completed') return { ok: run.conclusion === 'success', url: run.html_url, conclusion: run.conclusion };
    } catch {
      // network blip; try again
    }
  }
  return { ok: false, conclusion: 'timed out' };
}

async function work(task) {
  const set = (fields) => Object.assign(task, fields, { updated: Date.now() });
  try {
    set({ state: 'running' });
    await freshCheckout();
    log(task.id, 'running Claude Code for', task.requester, '-', task.request);
    const claude = await run(
      'claude',
      ['-p', prompt(task), '--output-format', 'json', '--dangerously-skip-permissions'],
      { timeout: CLAUDE_TIMEOUT_MS },
    );
    let result;
    try {
      result = JSON.parse(claude.out);
    } catch {
      throw new Error(`Claude Code didn't finish (exit ${claude.code}): ${(claude.err || claude.out).trim().slice(-300)}`);
    }
    const summary = String(result.result || '').trim().slice(0, 800);
    set({ summary, costUsd: result.total_cost_usd });
    log(task.id, 'Claude Code finished:', summary);
    if (result.is_error) throw new Error(`Claude Code stopped with an error (${result.subtype || 'unknown'}).`);

    const paths = changedPaths(await git('status', '--porcelain'));
    if (paths.length === 0) return set({ state: 'done' });
    const bad = paths.filter((p) => PROTECTED.some((d) => p.startsWith(d)));
    if (bad.length) throw new Error(`it tried to change files it isn't allowed to (${bad.join(', ')}).`);

    set({ state: 'testing' });
    const mvn = await run('mvn', ['-B', '-q', 'verify'], { timeout: TEST_TIMEOUT_MS });
    if (mvn.code !== 0) {
      log(task.id, 'tests failed:\n', mvn.out.slice(-4000), mvn.err.slice(-2000));
      throw new Error('the tests failed, so I left it out.');
    }

    set({ state: 'pushing' });
    await git('add', '-A');
    const title = task.request.replace(/\s+/g, ' ').slice(0, 60);
    const message = `Self-edit: ${title}${task.request.length > 60 ? '…' : ''}\n\n${summary}\n\n`
      + `Requested by ${task.requester} on Discord.\n\nCo-Authored-By: Claude <noreply@anthropic.com>`;
    const commit = await run('git', ['-c', 'user.name=Gunnabot', '-c', 'user.email=gunnabot@users.noreply.github.com',
      'commit', '-q', '-F', '-'], { input: message });
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

    const deploy = await waitForDeploy(sha);
    if (deploy.ok) set({ state: 'deployed', runUrl: deploy.url });
    else set({ state: 'deploy_failed', runUrl: deploy.url, error: `The GitHub Actions run ${deploy.conclusion}${deploy.url ? `: ${deploy.url}` : '.'}` });
    log(task.id, task.state);
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
    log(ask.id, 'answering for', ask.requester, '-', ask.question);
    const claude = await run('claude', ['-p', askPrompt(ask), '--output-format', 'json', '--model', ASK_MODEL,
      '--allowedTools', 'Read,Grep,Glob', '--disallowedTools', 'Bash,Edit,Write,NotebookEdit,WebFetch,WebSearch'],
      { cwd: ASK_DIR, timeout: ASK_TIMEOUT_MS });
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
async function readJson(req, res) {
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 20_000) {
      send(res, 413, { error: 'too long' });
      return null;
    }
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
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

function view(t) {
  const { id, state, summary, error, commit, runUrl, requester, request, created, updated } = t;
  return { id, state, summary, error, commit, runUrl, requester, request, created, updated };
}

http
  .createServer(async (req, res) => {
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
      const body = await readJson(req, res);
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
  })
  .listen(PORT, () => log(`coder listening on :${PORT} for ${REPO}@${BRANCH}`));
