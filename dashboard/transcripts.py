"""
Recorded ?chat sessions, for the Transcripts tab: what speech recognition
heard in each clip, what the cloud models heard (reference.json and
reference-2.json, from reference.py), and the corrections people make.

A session is a folder SessionRecorder wrote (session.json, transcript.jsonl,
clips/*.wav). Reviews are kept in <session>/corrections.json, one entry per
clip:
    "clips/201758-623_Clem_3163....wav": {
        "status": "fixed" | "ok" | "junk", "text": what was really said,
        "heard": what the speech model wrote, "name", "user_id", "at"
    }
fixed = put right; ok = checked and right as heard; junk = not worth
learning from (noise, crosstalk, nobody can tell). "fixed" and "ok" lines
are mirrored into reference_transcripts in the bot's database, as model
"human"; that is best effort, the folder is the record.

A line goes to the page with the cloud "opinions" and, when none of them
matches what the bot heard, a "flag": "likely" when two cloud models agree
with each other and not with the bot (probably a mistake, and their text the
fix), "unsure" when all differ or there is only one. Ums, stutters, case and
punctuation don't count as a difference.
"""
import json
import os
import re
import sqlite3
import threading
from datetime import datetime
from pathlib import Path

from sttbench import words

ROOTS = [Path(p) for p in os.environ.get(
    "SESSIONS", "/mnt/gunnabox2/Backup/musicbot/voice-sessions:/home/regan/musicbot/recordings").split(":") if p]
DATABASE = os.environ.get("DATABASE", "")
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
