"""
The bot's config.txt, for the Config tab: reads any key, and changes the few
in EDITABLE, each checked against its kind before it is written.

config.txt is HOCON. Only the forms the bot's config uses are understood:
key = "string", key = triple-quoted string, key = [a, b], key = word or number, at
the top level, with // or # comments. A value is replaced where it stands,
keeping a comment after it; a key that isn't there is added at the end.
Every write first copies the file to config.txt.bak-dashboard-<time>, to
the microsecond so quick saves don't overwrite each other (the last BACKUPS
are kept). Keys and tokens are never read out: only EDITABLE
keys, and the voice server's address, leave this module.

The bot reads its config when it starts, so a change takes effect at the
next restart; changed_since() tells the page whether one is waiting.
"""
import json
import os
import re
import shutil
import threading
import time
from datetime import datetime
from pathlib import Path

CONFIG = Path(os.environ.get("CONFIG", "/home/regan/musicbot/config.txt"))
REFERENCE = Path(os.environ.get(
    "REFERENCE", "/home/regan/actions-runner-musicbot/_work/MusicBot/MusicBot/src/main/resources/reference.conf"))
BACKUPS = 30
TAGS = ["laugh", "chuckle", "sigh", "gasp", "cough", "sniff", "groan", "whispering", "sarcastic", "happy",
        "surprised", "dramatic", "angry", "fear", "crying", "narration", "advertisement", "shush", "clear throat"]
MODEL = re.compile(r"[a-z0-9][a-z0-9._-]*/[A-Za-z0-9._:~-]+")

# key: (kind, group, label, what it does, limits)
EDITABLE = {
    "llmmodel": ("model", "Models", "Chat model",
                 "Answers when someone talks to the bot, and decides what to do about it.", None),
    "routerdecisionmodel": ("model?", "Models", "Router decision model",
                            "Decides which voice lines are for the bot and which command each is. Empty uses the chat router only.", None),
    "routermodel": ("model", "Models", "Router chat model",
                    "Works out the song, station or volume a command needs, and stands in when the decision model fails.", None),
    "researchmodel": ("model", "Models", "Research model",
                      "Looks things up on the web when a question needs it.", None),
    "llmmaxtokens": ("int", "Models", "Reply length limit",
                     "Most tokens the chat model may write in one reply.", (50, 2000)),
    "llmprompt": ("text", "Persona", "Persona",
                  "Who the bot is and how it talks. The command format is added in code.", None),
    "voicettsvoice": ("word", "Voice", "Voice",
                      "The speaking voice the speech server uses.", None),
    "voicettstags": ("tags", "Voice", "Expressive tags",
                     "Sounds and manners the bot may use in its replies. Chatterbox Turbo knows these; Kokoro knows none.", TAGS),
    "musicgain": ("int", "Music", "Music gain",
                  "How loud music plays, as a percentage of its volume, so the bot's voice stays clearly louder. 100 is no reduction.", (0, 100)),
    "skipratio": ("float", "Music", "Vote to skip",
                  "Share of listeners who must vote to skip a song.", (0, 1)),
    "alonetimeuntilstop": ("int", "Music", "Leave when alone after",
                           "Seconds alone in a voice channel before the bot leaves and clears the queue. 0 stays.", (0, 86400)),
    "databasespeechdays": ("int", "History", "Keep speech for",
                           "Days what people said in ?chat is kept in the history. 0 keeps it forever. Plays are always kept.", (0, 3650)),
}
lock = threading.Lock()


# ---- reading ----------------------------------------------------------------

def find(text, key):
    """(start of the value, end of the value, the value) for a top-level key, or None."""
    for m in re.finditer(r"(?m)^[ \t]*%s[ \t]*[=:][ \t]*" % re.escape(key), text):
        line_start = text.rfind("\n", 0, m.start()) + 1
        if depth(text[:line_start]):
            continue
        start = m.end()
        if text.startswith('"""', start):
            end = text.index('"""', start + 3) + 3
            return start, end, text[start + 3:end - 3]
        if text.startswith('"', start):
            value, size = json.JSONDecoder().raw_decode(text[start:])
            return start, start + size, value
        if text.startswith("[", start):
            end = text.index("]", start) + 1
            items = [i.strip().strip('"') for i in text[start + 1:end - 1].split(",")]
            return start, end, [i for i in items if i]
        end = re.search(r"\s*(//|#|\n|$)", text[start:]).start() + start
        word = text[start:end].strip()
        for kind in (int, float):
            try:
                return start, end, kind(word)
            except ValueError:
                pass
        return start, end, word
    return None


def depth(text):
    """How many { } blocks are open at the end of text, ignoring comments and strings."""
    text = re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"', '""', text)
    text = re.sub(r"(//|#)[^\n]*", "", text)
    return text.count("{") - text.count("}")


def read(path):
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def value(key):
    """The value config.txt gives a key, or reference.conf's default, or None."""
    for path in (CONFIG, REFERENCE):
        got = find(read(path), key)
        if got:
            return got[2]
    return None


def editable():
    live, defaults = read(CONFIG), read(REFERENCE)
    out = []
    for key, (kind, group, label, help_, limits) in EDITABLE.items():
        got, default = find(live, key), find(defaults, key)
        out.append({"key": key, "kind": kind, "group": group, "label": label, "help": help_,
                    "limits": limits, "set": bool(got), "value": (got or default or (0, 0, None))[2],
                    "default": default[2] if default else None})
    return out


def changed_since(started_ms):
    """Whether config.txt was written after the bot started."""
    try:
        return bool(started_ms) and CONFIG.stat().st_mtime * 1000 > started_ms + 2000
    except OSError:
        return False


# ---- writing ----------------------------------------------------------------

def check(key, new):
    """The value as it will be written, or ValueError saying what's wrong."""
    if key not in EDITABLE:
        raise ValueError("That setting can't be changed here.")
    kind, _, label, _, limits = EDITABLE[key]
    if kind in ("model", "model?"):
        new = str(new).strip()
        if not new and kind == "model?":
            return '""', new
        if not MODEL.fullmatch(new):
            raise ValueError("%s should be a model id like google/gemini-3.1-flash-lite." % label)
        return json.dumps(new), new
    if kind == "word":
        new = str(new).strip()
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,60}", new):
            raise ValueError("%s should be one word." % label)
        return json.dumps(new), new
    if kind == "text":
        new = str(new).strip()
        if not 20 <= len(new) <= 20000:
            raise ValueError("%s should be between 20 and 20,000 characters." % label)
        return json.dumps(new, ensure_ascii=False), new
    if kind == "tags":
        if not isinstance(new, list) or any(t not in limits for t in new):
            raise ValueError("Pick tags from the list.")
        new = [t for t in limits if t in new]
        return "[" + ", ".join(json.dumps(t) if " " in t else t for t in new) + "]", new
    if kind in ("int", "float"):
        try:
            number = int(new) if kind == "int" else float(new)
        except (TypeError, ValueError):
            raise ValueError("%s should be a number." % label)
        if not limits[0] <= number <= limits[1]:
            raise ValueError("%s should be from %s to %s." % (label, limits[0], limits[1]))
        return str(number), number
    raise ValueError(key)


def write(key, new):
    """Sets one key in config.txt, after a backup. Returns the value as written."""
    hocon, clean = check(key, new)
    with lock:
        text = read(CONFIG)
        if not text:
            raise OSError("config.txt can't be read")
        got = find(text, key)
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
        if got:
            text = text[:got[0]] + hocon + text[got[1]:]
        else:
            text = text.rstrip("\n") + "\n\n// set from the dashboard, %s\n%s = %s\n" % (stamp[:8], key, hocon)
        shutil.copy2(CONFIG, CONFIG.with_name(CONFIG.name + ".bak-dashboard-" + stamp))
        tmp = CONFIG.with_name(CONFIG.name + ".tmp")
        tmp.write_text(text, encoding="utf-8")
        os.replace(tmp, CONFIG)
        old = sorted(CONFIG.parent.glob(CONFIG.name + ".bak-dashboard-*"))
        for path in old[:-BACKUPS]:
            path.unlink(missing_ok=True)
    print("config: %s changed from the dashboard" % key, flush=True)
    return clean
