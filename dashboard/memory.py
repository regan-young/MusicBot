"""
What Gunnabot remembers: voice-memory.json, beside her config. Facts about
each person, keyed by Discord user id, and shared memories of the group
under "group". The bot reads the file again whenever it changes, so edits
here take effect on her next reply, with no restart.
"""
import json
import os
import threading
from pathlib import Path

MEMORY = Path(os.environ.get("MEMORY", str(Path.home() / "musicbot" / "voice-memory.json")))
GROUP = "group"
MAX_NOTE = 300
lock = threading.Lock()


def _read():
    try:
        return json.loads(MEMORY.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}


def read():
    """{"group": [...], "people": [{"id", "name", "notes"}]}, people in name order."""
    d = _read()
    people = [{"id": k, "name": v.get("name", "?"), "notes": v.get("notes", [])}
              for k, v in d.items() if k != GROUP and isinstance(v, dict)]
    people.sort(key=lambda p: p["name"].lower())
    return {"group": d.get(GROUP, {}).get("notes", []), "people": people}


def edit(who, index, before, text):
    """
    Changes one note. who is a user id or "group"; index -1 adds text as a new
    note; otherwise before must still be the note at index (so two edits can't
    cross), and empty text deletes it. Returns the new state.
    """
    if not isinstance(who, str) or not (who == GROUP or who.isdigit()) or not isinstance(index, int) \
            or not isinstance(text, str) or (index >= 0 and not isinstance(before, str)):
        raise ValueError("bad request")
    text = " ".join(text.split())
    if len(text) > MAX_NOTE:
        raise ValueError("keep a memory under %d characters" % MAX_NOTE)
    with lock:
        d = _read()
        entry = d.get(who)
        if entry is None:
            if who != GROUP:
                raise KeyError("no such person")
            entry = d[GROUP] = {"name": "Everyone", "notes": []}
        notes = entry.setdefault("notes", [])
        if index < 0:
            if not text:
                raise ValueError("write something to remember")
            if text.lower() not in (n.lower() for n in notes):
                notes.append(text)
        else:
            if index >= len(notes) or notes[index] != before:
                raise LookupError("that memory changed since the page loaded; reload and try again")
            if text:
                notes[index] = text
            else:
                del notes[index]
        tmp = MEMORY.with_name(MEMORY.name + ".dashboard.tmp")
        tmp.write_text(json.dumps(d, indent=2, ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, MEMORY)
    return read()
