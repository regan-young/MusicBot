#!/usr/bin/env python3
"""
A small web page for putting right what speech recognition misheard.

It reads the ?chat sessions SessionRecorder saved (session.json,
transcript.jsonl and clips/*.wav in a folder per session), plays each clip
beside what the speech model made of it, and keeps what a person says it
should have been.

What it writes:

  <session>/corrections.json   one entry per reviewed clip:
        "clips/201758-623_Clem_3163....wav": {
            "status": "fixed" | "ok" | "junk",
            "text":   what was really said ("fixed" and "ok"),
            "heard":  what the speech model wrote,
            "name", "user_id", "at"
        }
      fixed = the text was wrong and has been put right; ok = checked and
      right as heard; junk = not worth learning from (noise, two people at
      once, nobody can tell).

What it reads besides: <session>/reference.json and reference-2.json, other
transcripts of each clip from cloud speech models (reference.py makes them).
Each line goes to the page with those "opinions" and, when none of them
matches what the bot heard, a "flag":
  likely   two cloud models agree with each other and not with the bot:
           probably a mistake, and their text is probably the fix
  unsure   every model heard something different, or there is only one
           other opinion: it needs ears
Ums, stutters, case and punctuation don't count as a difference.

  reference_transcripts in the bot's database, model "human", for "fixed"
  and "ok" lines, so stt_by_person counts them. Best effort: the folder is
  the record, and a busy or missing database loses nothing.

It has no login of its own. On the home network it needs none: it answers
clients in LAN (and this machine) that ask for it directly and by one of its
own names, and refuses anything that arrived through a proxy. From anywhere
else it is reached only through a reverse proxy that asks for a password
first (Caddy, with basic_auth): requests from PROXY are answered when they
are for the PUBLIC host name, and nothing else is. Never proxy to it without
a password in front.

Settings, from the environment:
  BIND      address to listen on              (127.0.0.1)
  PORT                                        (8099)
  LAN       networks allowed in, comma list   (the /24 of BIND)
  HOSTS     extra host names it answers to    (none)
  PROXY_BIND  second address to listen on, for the proxy   (none)
  PROXY       network the proxy connects from              (none)
  PUBLIC      host name the proxy serves it under          (none)
  SESSIONS  session roots, separated by ':'   (the NAS folder, then the bot's own)
  DATABASE  the bot's gunnabot.db             (none: no mirroring)

Python 3.9+, standard library only.
"""
import ipaddress
import json
import os
import re
import socket
import sqlite3
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

from sttbench import words

BIND = os.environ.get("BIND", "127.0.0.1")
PORT = int(os.environ.get("PORT", "8099"))
ROOTS = [Path(p) for p in os.environ.get(
    "SESSIONS", "/mnt/gunnabox2/Backup/musicbot/voice-sessions:/home/regan/musicbot/recordings").split(":") if p]
DATABASE = os.environ.get("DATABASE", "")
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

PAGE = Path(__file__).with_name("index.html")
CLIP = re.compile(r"clips/[^/\\]+\.wav")
STATUSES = ("fixed", "ok", "junk")
REFERENCES = ("reference.json", "reference-2.json")
write_lock = threading.Lock()
_cache = {}


# ---- sessions -------------------------------------------------------------

def session_dir(name):
    """The folder of a session, or None; the name must be one that is really listed."""
    if not name or "/" in name or "\\" in name or name.startswith("."):
        return None
    for root in ROOTS:
        d = root / name
        if d.is_dir() and (d / "transcript.jsonl").is_file():
            return d
    return None


def mtime(path):
    try:
        return path.stat().st_mtime_ns
    except OSError:
        return 0


def models(d):
    """The cloud models that have given this session a transcript."""
    return [m for m in (read_json(d / name, {}).get("model", "") for name in REFERENCES) if m]


def read_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def corrections(d):
    got = read_json(d / "corrections.json", {})
    return got if isinstance(got, dict) else {}


def lines(d):
    """The session in the order it happened: what was heard, with how it was routed, and what the bot said."""
    path = d / "transcript.jsonl"
    stat = path.stat()
    key = (stat.st_mtime_ns, stat.st_size) + tuple(mtime(d / name) for name in REFERENCES)
    cached = _cache.get(str(d))
    if cached and cached[0] == key:
        return cached[1]
    heard, out = {}, []
    with open(path, encoding="utf-8") as f:
        for raw in f:
            try:
                e = json.loads(raw)
            except ValueError:
                continue
            kind = e.get("type")
            if kind == "heard" and e.get("clip"):
                line = {"clip": e["clip"], "time": e.get("time", ""), "name": e.get("name", "?"),
                        "user_id": e.get("user_id", ""), "text": e.get("text", ""), "audio_ms": e.get("audio_ms", 0)}
                heard[e["clip"]] = line
                out.append(line)
            elif kind == "routed" and e.get("clip") in heard:
                heard[e["clip"]].update(outcome=e.get("outcome"), for_bot=bool(e.get("for_bot")),
                                        named=bool(e.get("named")), confidence=e.get("confidence"), args=e.get("args"))
            elif kind == "bot":
                out.append({"bot": True, "time": e.get("time", ""), "text": e.get("text", "")})
    out.sort(key=lambda l: l["time"])
    for name in REFERENCES:
        reference = read_json(d / name, {})
        model = reference.get("model", "")
        for clip, text in (reference.get("lines") or {}).items():
            if clip in heard and isinstance(text, str):
                heard[clip].setdefault("opinions", []).append({"model": model, "text": text})
    for line in heard.values():
        others = [words(o["text"]) for o in line.get("opinions", [])]
        if not others or any(o == words(line["text"]) for o in others):
            continue
        line["flag"] = "likely" if len(others) > 1 and all(o == others[0] for o in others) else "unsure"
    _cache[str(d)] = (key, out)
    return out


def sessions():
    found = {}
    for root in ROOTS:
        try:
            names = sorted(os.listdir(root))
        except OSError:
            continue
        for name in names:
            d = session_dir(name)
            if d is None or name in found:
                continue
            info = read_json(d / "session.json", {})
            speakers = info.get("speakers", {}) if isinstance(info, dict) else {}
            done = corrections(d)
            found[name] = {
                "id": name, "guild": info.get("guild", ""), "channel": info.get("channel", ""),
                "started": info.get("started", ""), "ended": info.get("ended", ""),
                "clips": sum(s.get("clips", 0) for s in speakers.values()),
                "speakers": sorted({(s.get("names") or ["?"])[-1] for s in speakers.values()}, key=str.lower),
                "reviewed": len(done), "fixed": sum(1 for c in done.values() if c.get("status") == "fixed"),
                "reference": models(d),
            }
    return sorted(found.values(), key=lambda s: s["id"], reverse=True)


# ---- saving ---------------------------------------------------------------

def save(d, clip, status, text):
    """Records one review, or with no status takes it back. Returns the entry as kept."""
    line = next((l for l in lines(d) if l.get("clip") == clip), None)
    if line is None:
        raise KeyError(clip)
    entry = None
    with write_lock:
        done = corrections(d)
        if status is None:
            done.pop(clip, None)
        else:
            if status == "junk":
                text = ""
            elif text == line["text"]:
                status = "ok"
            entry = {"status": status, "text": text, "heard": line["text"], "name": line["name"],
                     "user_id": line["user_id"], "at": datetime.now().astimezone().isoformat(timespec="seconds")}
            done[clip] = entry
        target = d / "corrections.json"
        tmp = d / "corrections.json.tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(dict(sorted(done.items())), f, ensure_ascii=False, indent=1)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, target)
    mirror(line, entry)
    return entry


def mirror(line, entry):
    """Keeps the bot's database in step: a "human" reference transcript for a line that has one."""
    if not DATABASE or not os.path.isfile(DATABASE):
        return
    try:
        heard_at = int(datetime.fromisoformat(line["time"]).timestamp() * 1000)
        db = sqlite3.connect(DATABASE, timeout=5)
        try:
            row = db.execute("SELECT id FROM voice_lines WHERE clip = ? AND heard_at BETWEEN ? AND ?",
                             (line["clip"], heard_at - 5, heard_at + 5)).fetchone()
            if row is None:
                return
            if entry and entry["status"] in ("fixed", "ok"):
                db.execute("INSERT OR REPLACE INTO reference_transcripts (line_id, model, text, created_at) "
                           "VALUES (?, 'human', ?, ?)", (row[0], entry["text"], int(datetime.now().timestamp() * 1000)))
            else:
                db.execute("DELETE FROM reference_transcripts WHERE line_id = ? AND model = 'human'", (row[0],))
            db.commit()
        finally:
            db.close()
    except (sqlite3.Error, ValueError, OSError) as e:
        print("database not updated for", line["clip"], "-", e, flush=True)


# ---- http -----------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "transcripts"

    def log_message(self, fmt, *args):
        if not self.path.startswith("/clip/"):
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
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        if not self.local():
            return self.send(403, {"error": "this page is for the home network only"})
        parts = [unquote(p) for p in urlparse(self.path).path.split("/") if p]
        try:
            if not parts:
                return self.send(200, PAGE.read_bytes(), "text/html; charset=utf-8")
            if parts == ["api", "sessions"]:
                return self.send(200, sessions())
            if len(parts) == 3 and parts[:2] == ["api", "session"]:
                d = session_dir(parts[2])
                if d is None:
                    return self.send(404, {"error": "no such session"})
                return self.send(200, {"id": parts[2], "info": read_json(d / "session.json", {}),
                                       "reference": models(d),
                                       "lines": lines(d), "corrections": corrections(d)})
            if len(parts) == 3 and parts[0] == "clip":
                return self.clip(session_dir(parts[1]), "clips/" + parts[2])
        except OSError as e:
            return self.send(500, {"error": str(e)})
        self.send(404, {"error": "not found"})

    do_HEAD = do_GET

    def clip(self, d, clip):
        if d is None or not CLIP.fullmatch(clip):
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
        if len(parts) != 3 or parts[:2] != ["api", "session"]:
            return self.send(404, {"error": "not found"})
        d = session_dir(parts[2])
        if d is None:
            return self.send(404, {"error": "no such session"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size > 100_000:
                return self.send(413, {"error": "too long"})
            body = json.loads(self.rfile.read(size))
            clip, status, text = body["clip"], body.get("status"), body.get("text") or ""
            if not isinstance(clip, str) or not CLIP.fullmatch(clip) or not isinstance(text, str) \
                    or (status is not None and status not in STATUSES):
                raise ValueError
            text = " ".join(text.split())
            if status in ("fixed", "ok") and not text:
                raise ValueError
        except (ValueError, KeyError, TypeError):
            return self.send(400, {"error": "bad request"})
        try:
            return self.send(200, {"clip": clip, "correction": save(d, clip, status, text)})
        except KeyError:
            return self.send(404, {"error": "no such clip"})
        except OSError as e:
            return self.send(500, {"error": "could not save: %s" % e})


if __name__ == "__main__":
    servers = [ThreadingHTTPServer((address, PORT), Handler) for address in (BIND, PROXY_BIND) if address]
    for server in servers:
        server.daemon_threads = True
    print("transcripts on http://%s:%d for %s; sessions in %s" % (
        BIND, PORT, ", ".join(str(n) for n in ALLOWED), ", ".join(str(r) for r in ROOTS)), flush=True)
    if PROXY_BIND:
        print("and on %s:%d for the proxy at %s, as %s" % (PROXY_BIND, PORT, ", ".join(str(n) for n in PROXY), PUBLIC), flush=True)
    for server in servers[1:]:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    servers[0].serve_forever()
