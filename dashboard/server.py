#!/usr/bin/env python3
"""
The Gunnabot dashboard: one page with tabs for how the bot is doing
(Overview), how well it does it (Metrics), what it's saying (Logs), what it
heard (Transcripts) and a few of its settings (Config).

    bot.py          containers, the Mac's speech server, logs, CPU and memory
    stats.py        sessions, reply times, music and speech recognition
    transcripts.py  recorded sessions and their corrections
    settings.py     the editable part of config.txt
    static/         the page

It has no login of its own. On the home network it needs none: it answers
clients in LAN (and this machine) that ask for it directly and by one of its
own names, and refuses anything that arrived through a proxy. From anywhere
else it is reached only through a reverse proxy that asks for a password
first (Caddy, with basic_auth): requests from PROXY are answered when they
are for the PUBLIC host name, and nothing else is. Never proxy to it without
a password in front: it can change the bot's config and restart it.

Settings, from the environment:
  BIND        address to listen on                       (127.0.0.1)
  PORT                                                   (8099)
  LAN         networks allowed in, comma list            (the /24 of BIND)
  HOSTS       extra host names it answers to             (none)
  PROXY_BIND  second address to listen on, for the proxy (none)
  PROXY       network the proxy connects from            (none)
  PUBLIC      host name the proxy serves it under        (none)
  SESSIONS    session roots, separated by ':'            (the NAS folder, then the bot's own)
  DATABASE    the bot's gunnabot.db                      (none: no history numbers)
  CONFIG, REFERENCE, REPO, RECORDINGS, LOKI, PROMETHEUS, BOT_CONTAINER, CODER_CONTAINER: see the modules

Python 3.9+, standard library only.
"""
import ipaddress
import json
import os
import re
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

import bot
import memory
import settings
import stats
import transcripts

BIND = os.environ.get("BIND", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8099"))
LOOPBACK = [ipaddress.ip_network("127.0.0.0/8"), ipaddress.ip_network("::1/128")]


def _lan():
    given = os.environ.get("LAN", "")
    if given:
        return [ipaddress.ip_network(n.strip()) for n in given.split(",") if n.strip()]
    if ipaddress.ip_address(BIND).is_loopback or ipaddress.ip_address(BIND).is_unspecified:
        return []
    return [ipaddress.ip_network(BIND + "/24", strict=False)]


ALLOWED = LOOPBACK + _lan()
PROXY_BIND = os.environ.get("PROXY_BIND", "")
PROXY = [ipaddress.ip_network(n.strip()) for n in os.environ.get("PROXY", "").split(",") if n.strip()]
PUBLIC = os.environ.get("PUBLIC", "").strip().lower()
HOSTS = {h.strip().lower() for h in os.environ.get("HOSTS", "").split(",") if h.strip()} \
    | {BIND, "localhost", "127.0.0.1", socket.gethostname().lower()}

STATIC = Path(__file__).with_name("static")
STATIC_FILE = re.compile(r"[a-z0-9_-]+\.(js|css|svg)")
TYPES = {"js": "text/javascript; charset=utf-8", "css": "text/css; charset=utf-8", "svg": "image/svg+xml"}


# The page asks again every few seconds, from every open tab; answers this fresh are shared
_memo, _memo_locks, _memo_lock = {}, {}, threading.Lock()


def memo(key, ttl, fn):
    with _memo_lock:
        lock = _memo_locks.setdefault(key, threading.Lock())
    with lock:
        hit = _memo.get(key)
        if hit and time.monotonic() - hit[0] < ttl:
            return hit[1]
        value = fn()
        _memo[key] = (time.monotonic(), value)
        return value


def overview():
    c = bot.containers()
    started = c.get(bot.BOT, {}).get("started")
    return {"containers": c, "deployed": bot.deployed(), "voice_server": bot.voice_server(),
            "activity": bot.activity(), "history": stats.overview(), "warnings": stats.warnings(),
            "review": stats.review_progress(), "config_pending": settings.changed_since(started), "now": int(time.time() * 1000)}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "gunnabot-dashboard"

    def log_message(self, fmt, *args):
        if not self.path.startswith(("/clip/", "/static/", "/api/logs", "/api/overview")):
            print(self.headers.get("X-Forwarded-For", self.client_address[0]) if self.headers else "-", fmt % args, flush=True)

    def local(self):
        """The home network, asked directly and by a name of ours; or the password-checking proxy, for the public name."""
        try:
            ip = ipaddress.ip_address(self.client_address[0].split("%")[0])
        except ValueError:
            return False
        if ip.version == 6 and ip.ipv4_mapped:
            ip = ip.ipv4_mapped
        host = self.headers.get("Host", "").lower()
        host = host.rsplit(":", 1)[0] if ":" in host and not host.endswith("]") else host
        if PUBLIC and any(ip in net for net in PROXY):
            return host == PUBLIC
        if not any(ip in net for net in ALLOWED):
            return False
        if any(h in self.headers for h in ("X-Forwarded-For", "Forwarded", "X-Real-IP", "Via")):
            return False
        return host in HOSTS

    def send(self, code, body=b"", kind="application/json", extra=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    # ---- reading ----

    def do_GET(self):
        if not self.local():
            return self.send(403, {"error": "this page is for the home network only"})
        url = urlparse(self.path)
        parts = [unquote(p) for p in url.path.split("/") if p]
        q = {k: v[-1] for k, v in parse_qs(url.query).items()}
        try:
            if not parts:
                return self.send(200, (STATIC / "index.html").read_bytes(), "text/html; charset=utf-8")
            if len(parts) == 2 and parts[0] == "static" and STATIC_FILE.fullmatch(parts[1]):
                path = STATIC / parts[1]
                if path.is_file():
                    return self.send(200, path.read_bytes(), TYPES[parts[1].rsplit(".", 1)[1]])
            if parts == ["api", "overview"]:
                return self.send(200, memo(("overview",), 3, overview))
            if parts == ["api", "resources"]:
                hours = min(float(q.get("hours", 24)), 24 * 30)
                return self.send(200, memo(("resources", hours), 30, lambda: bot.resources(hours)))
            if parts == ["api", "metrics"]:
                days = int(q.get("days", 30))
                days = days if days > 0 else 0
                return self.send(200, memo(("metrics", days), 20, lambda: stats.metrics(days)))
            if parts == ["api", "logs"]:
                levels = tuple(l for l in q.get("levels", "").split(",") if l in ("DEBUG", "INFO", "WARN", "ERROR"))
                return self.send(200, bot.logs(q.get("container", bot.BOT), q.get("q", "")[:200], levels,
                                               int(q["start"]) if q.get("start") else None,
                                               int(q["end"]) if q.get("end") else None,
                                               int(q.get("limit", 500)), q.get("order", "new") != "old"))
            if parts == ["api", "config"]:
                started = bot.containers().get(bot.BOT, {}).get("started")
                return self.send(200, {"keys": settings.editable(), "pending": settings.changed_since(started)})
            if parts == ["api", "memory"]:
                return self.send(200, memory.read())
            if parts == ["api", "sessions"]:
                return self.send(200, transcripts.sessions())
            if len(parts) == 3 and parts[:2] == ["api", "session"]:
                d = transcripts.session_dir(parts[2])
                if d is None:
                    return self.send(404, {"error": "no such session"})
                # v, cv and have: the versions and line count the page already has, so it gets only what changed
                return self.send(200, transcripts.session(d, parts[2], q.get("v"), q.get("cv"), int(q.get("have", 0))))
            if len(parts) == 3 and parts[0] == "clip":
                return self.clip(transcripts.session_dir(parts[1]), "clips/" + parts[2])
        except (ValueError, KeyError) as e:
            return self.send(400, {"error": "bad request: %s" % e})
        except OSError as e:
            return self.send(500, {"error": str(e)})
        self.send(404, {"error": "not found"})

    do_HEAD = do_GET

    def clip(self, d, clip):
        if d is None or not transcripts.CLIP.fullmatch(clip):
            return self.send(404, {"error": "no such clip"})
        path = d / clip
        if not path.is_file():
            return self.send(404, {"error": "no such clip"})
        data = path.read_bytes()
        asked = re.fullmatch(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
        if asked and (asked.group(1) or asked.group(2)) and data:
            if asked.group(1):
                start = int(asked.group(1))
                end = min(int(asked.group(2)) if asked.group(2) else len(data) - 1, len(data) - 1)
            else:
                start, end = max(len(data) - int(asked.group(2)), 0), len(data) - 1
            if start > end:
                return self.send(416, b"", "audio/wav", {"Content-Range": "bytes */%d" % len(data)})
            return self.send(206, data[start:end + 1], "audio/wav",
                             {"Content-Range": "bytes %d-%d/%d" % (start, end, len(data)), "Accept-Ranges": "bytes"})
        self.send(200, data, "audio/wav", {"Accept-Ranges": "bytes"})

    # ---- changing ----

    def do_POST(self):
        if not self.local():
            return self.send(403, {"error": "this page is for the home network only"})
        # a page on another site can't post here: it can't send JSON without asking first, and its Origin isn't ours
        origin = self.headers.get("Origin")
        if origin and urlparse(origin).hostname not in HOSTS | {PUBLIC}:
            return self.send(403, {"error": "wrong origin"})
        if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            return self.send(415, {"error": "send JSON"})
        parts = [unquote(p) for p in urlparse(self.path).path.split("/") if p]
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size > 100_000:
                return self.send(413, {"error": "too long"})
            body = json.loads(self.rfile.read(size) or b"{}")
            if not isinstance(body, dict):
                raise ValueError
        except ValueError:
            return self.send(400, {"error": "bad request"})
        if parts == ["api", "config"]:
            try:
                return self.send(200, {"key": body.get("key"), "value": settings.write(body.get("key"), body.get("value"))})
            except ValueError as e:
                return self.send(400, {"error": str(e)})
            except OSError as e:
                return self.send(500, {"error": "could not save: %s" % e})
        if parts == ["api", "bot", "restart"]:
            got = bot.restart(bool(body.get("force")))
            print("bot restart from the dashboard:", "done" if got["ok"] else got["error"], flush=True)
            return self.send(200 if got["ok"] else 409 if got.get("busy") else 500, got)
        if parts == ["api", "memory"]:
            try:
                return self.send(200, memory.edit(body.get("who"), body.get("index"), body.get("before"), body.get("text", "")))
            except ValueError as e:
                return self.send(400, {"error": str(e)})
            except KeyError as e:
                return self.send(404, {"error": e.args[0]})
            except LookupError as e:
                return self.send(409, {"error": str(e)})
            except OSError as e:
                return self.send(500, {"error": "could not save: %s" % e})
        if len(parts) == 3 and parts[:2] == ["api", "session"]:
            return self.review(parts[2], body)
        self.send(404, {"error": "not found"})

    def review(self, name, body):
        d = transcripts.session_dir(name)
        if d is None:
            return self.send(404, {"error": "no such session"})
        try:
            clip, status, text = body["clip"], body.get("status"), body.get("text") or ""
            if not isinstance(clip, str) or not transcripts.CLIP.fullmatch(clip) or not isinstance(text, str) \
                    or (status is not None and status not in transcripts.STATUSES):
                raise ValueError
            text = " ".join(text.split())
            if status in ("fixed", "ok") and not text:
                raise ValueError
        except (ValueError, KeyError, TypeError):
            return self.send(400, {"error": "bad request"})
        try:
            return self.send(200, {"clip": clip, "correction": transcripts.save(d, clip, status, text)})
        except KeyError:
            return self.send(404, {"error": "no such clip"})
        except OSError as e:
            return self.send(500, {"error": "could not save: %s" % e})


if __name__ == "__main__":
    servers = [ThreadingHTTPServer((address, PORT), Handler) for address in (BIND, PROXY_BIND) if address]
    for server in servers:
        server.daemon_threads = True
    print("dashboard on http://%s:%d for %s; sessions in %s" % (
        BIND, PORT, ", ".join(str(n) for n in ALLOWED), ", ".join(str(r) for r in transcripts.ROOTS)), flush=True)
    if PROXY_BIND:
        print("and on %s:%d for the proxy at %s, as %s" % (PROXY_BIND, PORT, ", ".join(str(n) for n in PROXY), PUBLIC), flush=True)
    for server in servers[1:]:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    servers[0].serve_forever()
