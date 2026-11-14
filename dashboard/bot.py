"""
The running bot, for the Overview and Logs tabs: its containers, the speech
server on the Mac, whether anyone is talking to it, its logs, and the
containers' CPU and memory.

Logs come from Loki (promtail ships every container's output there, so they
outlast restarts); if Loki doesn't answer, from `docker logs`, which only has
the current container. CPU and memory come from Prometheus (cAdvisor).
Anything that looks like a key or token is masked before it leaves here.
"""
import json
import os
import re
import subprocess
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

import settings

BOT = os.environ.get("BOT_CONTAINER", "musicbot")
CODER = os.environ.get("CODER_CONTAINER", "musicbot-coder")
CONTAINERS = (BOT, CODER)
LOKI = os.environ.get("LOKI", "http://127.0.0.1:3100")
PROMETHEUS = os.environ.get("PROMETHEUS", "http://127.0.0.1:9090")
RECORDINGS = Path(os.environ.get("RECORDINGS", "/home/regan/musicbot/recordings"))
REPO = Path(os.environ.get("REPO", "/home/regan/actions-runner-musicbot/_work/MusicBot/MusicBot"))

# keys and tokens, wherever they turn up in a log line
SECRETS = [
    re.compile(r"sk-[A-Za-z0-9_-]{2,}-[A-Za-z0-9_-]{8,}"),                 # OpenRouter, Anthropic, OpenAI
    re.compile(r"\b[MNO][A-Za-z\d_-]{23,27}\.[\w-]{6}\.[\w-]{27,40}\b"),   # Discord bot token
    re.compile(r"(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{12,}"),
    re.compile(r"(?i)((?:api[_-]?key|token|secret|password|authorization)[\"']?\s*[:=]\s*[\"']?)[^\s\"',}]{6,}"),
]
LINE = re.compile(r"^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d+) \[([^\]]*)\] (\w+)\s+(\S+) - (.*)$", re.S)


def redact(text):
    for pattern in SECRETS:
        text = pattern.sub(lambda m: (m.group(1) if m.groups() else "") + "••••", text)
    return text


def run(*args, timeout=10):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)


def get_json(url, timeout=5):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.loads(r.read())


def iso_ms(text):
    """Docker's timestamps (nanoseconds, Z) as epoch milliseconds."""
    if not text or text.startswith("0001"):
        return None
    text = re.sub(r"(\.\d{6})\d*", r"\1", text).replace("Z", "+00:00")
    return int(datetime.fromisoformat(text).timestamp() * 1000)


# ---- status -----------------------------------------------------------------

def containers():
    got = run("docker", "inspect", *CONTAINERS)
    out = {}
    for c in json.loads(got.stdout or "[]"):
        state = c.get("State", {})
        out[c["Name"].lstrip("/")] = {
            "running": state.get("Running", False), "status": state.get("Status"),
            "health": (state.get("Health") or {}).get("Status"),
            "started": iso_ms(state.get("StartedAt")), "restarts": c.get("RestartCount", 0),
        }
    for name in CONTAINERS:
        out.setdefault(name, {"running": False, "status": "missing"})
    return out


def deployed():
    """The commit the deploy last checked out, which is what the bot was built from."""
    got = run("git", "-C", str(REPO), "log", "-1", "--format=%h%x00%s%x00%cI")
    if got.returncode or not got.stdout:
        return None
    sha, subject, when = got.stdout.strip().split("\0")
    return {"sha": sha, "subject": subject, "at": when}


def voice_server():
    """The speech server (voiceapi) on the Mac: up, how quickly it answers, what it has loaded."""
    base = settings.value("voiceapi") or ""
    if not base:
        return {"configured": False}
    host = urllib.parse.urlparse(base).hostname
    started = time.time()
    try:
        got = get_json(base.rstrip("/") + "/v1/models", timeout=3)
        return {"configured": True, "up": True, "host": host, "ms": int((time.time() - started) * 1000),
                "models": [m.get("id") for m in got.get("data", [])]}
    except Exception as e:
        return {"configured": True, "up": False, "host": host, "error": type(e).__name__}


def activity():
    """Whether people are talking to the bot now, from the last ten minutes of its own log."""
    got = run("docker", "logs", "--since", "10m", BOT)
    heard = [l for l in (got.stdout + got.stderr).splitlines() if " - Heard " in l]
    last = None
    if heard:
        m = LINE.match(heard[-1])
        if m:
            last = int(datetime.fromisoformat(m.group(1)).replace(tzinfo=timezone.utc).timestamp() * 1000)
    session = None
    try:
        folders = [p for p in RECORDINGS.iterdir() if p.is_dir()]
        newest = max(folders, key=lambda p: p.stat().st_mtime) if folders else None
        if newest and time.time() - newest.stat().st_mtime < 600:
            session = newest.name
    except OSError:
        pass
    return {"heard_10m": len(heard), "last_heard": last, "live": bool(last and time.time() * 1000 - last < 300_000),
            "recording": session}


def restart(force=False):
    """Restarts the bot so a config change takes effect; refuses while people are talking to it, unless forced."""
    if not force and activity()["live"]:
        return {"ok": False, "busy": True, "error": "People are talking to the bot right now."}
    got = run("docker", "restart", BOT, timeout=90)
    return {"ok": got.returncode == 0, "error": got.stderr.strip()[:300] if got.returncode else ""}


# ---- logs -------------------------------------------------------------------

def logs(container=BOT, query="", levels=(), start_ms=None, end_ms=None, limit=500, newest_first=True):
    """
    Log lines, newest first unless asked otherwise: [{ts, level, thread, logger, msg}].
    query is plain text, matched ignoring case; levels limits to INFO, WARN, ERROR...
    """
    if container not in CONTAINERS:
        raise ValueError(container)
    end_ms = end_ms or int(time.time() * 1000)
    start_ms = start_ms or end_ms - 3600_000
    limit = max(1, min(int(limit), 5000))
    try:
        entries, source = _loki(container, query, levels, start_ms, end_ms, limit, newest_first), "loki"
    except Exception:
        entries, source = _docker(container, query, levels, start_ms, end_ms, limit, newest_first), "docker"
    return {"source": source, "entries": entries}


def _loki(container, query, levels, start_ms, end_ms, limit, newest_first):
    logql = '{container_name="%s"}' % container
    if levels:
        logql += ' |~ "\\\\] (%s) "' % "|".join(re.escape(l) for l in levels)
    if query:
        logql += ' |~ %s' % json.dumps("(?i)" + re.escape(query))
    got = get_json(LOKI + "/loki/api/v1/query_range?" + urllib.parse.urlencode({
        "query": logql, "start": start_ms * 1_000_000, "end": end_ms * 1_000_000, "limit": limit,
        "direction": "backward" if newest_first else "forward"}), timeout=15)
    raw = [(int(ts) // 1_000_000, line) for stream in got["data"]["result"] for ts, line in stream["values"]]
    raw.sort(key=lambda e: e[0], reverse=newest_first)
    return [entry(ts, line) for ts, line in raw[:limit]]


def _docker(container, query, levels, start_ms, end_ms, limit, newest_first):
    got = run("docker", "logs", "--timestamps", "--since", str(start_ms / 1000), "--until", str(end_ms / 1000), container,
              timeout=20)
    out = []
    for line in (got.stdout + got.stderr).splitlines():
        stamp, _, rest = line.partition(" ")
        e = entry(iso_ms(stamp) or 0, rest)
        if levels and e["level"] not in levels:
            continue
        if query and query.lower() not in rest.lower():
            continue
        out.append(e)
    out.sort(key=lambda e: e["ts"], reverse=newest_first)
    return out[:limit]


def entry(ts, line):
    line = redact(line.rstrip("\n"))
    m = LINE.match(line)
    if not m:
        return {"ts": ts, "level": "", "thread": "", "logger": "", "msg": line}
    return {"ts": ts, "level": m.group(3), "thread": m.group(2), "logger": m.group(4), "msg": m.group(5)}


def loki_lines(logql, start_ms, end_ms):
    """Every matching line in a window, oldest first, for counting things in the logs: [(ts_ms, line)]."""
    out, cursor = [], start_ms * 1_000_000
    end = end_ms * 1_000_000
    while cursor < end:
        got = get_json(LOKI + "/loki/api/v1/query_range?" + urllib.parse.urlencode({
            "query": logql, "start": cursor, "end": end, "limit": 5000, "direction": "forward"}), timeout=30)
        batch = sorted((int(ts), line) for s in got["data"]["result"] for ts, line in s["values"])
        out += [(ts // 1_000_000, line) for ts, line in batch]
        if len(batch) < 5000:
            break
        cursor = batch[-1][0] + 1
    return out


# ---- resources --------------------------------------------------------------

def resources(hours=24):
    """CPU (cores) and memory (bytes) of both containers over the last hours, from Prometheus."""
    end = int(time.time())
    start = end - int(hours * 3600)
    # Prometheus has a sample every 15 s: short ranges get every one, and CPU averaged over a minute, not five
    step = max(15, int(hours * 3600 / 240))
    window = "1m" if hours <= 1 else "5m"
    queries = {
        "cpu": 'sum by (name) (rate(container_cpu_usage_seconds_total{name=~"%s"}[' + window + ']))',
        "memory": 'sum by (name) (container_memory_working_set_bytes{name=~"%s"})',
    }
    out = {"step": step}
    names = "|".join(CONTAINERS)
    for key, q in queries.items():
        try:
            got = get_json(PROMETHEUS + "/api/v1/query_range?" + urllib.parse.urlencode(
                {"query": q % names, "start": start, "end": end, "step": step}), timeout=10)
            out[key] = {r["metric"].get("name", "?"): [[int(t) * 1000, float(v)] for t, v in r["values"]]
                        for r in got["data"]["result"]}
        except Exception as e:
            out[key] = {}
            out["error"] = type(e).__name__
    return out
