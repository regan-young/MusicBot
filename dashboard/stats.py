"""
Numbers for the Overview and Metrics tabs, from the bot's history database
(read only) and, for how long replies take, from its logs in Loki.

A voice session is the bot's ?chat session: the database's voice_lines carry
its start time in "session". Reply timings are only in the logs:
  "Heard ... (… smart turn 0.98 after 256 ms, STT 240 ms, router 690 ms …) … -> chat"
  "LLM 1273 ms: …"
  "Spoke in 1798 ms, 4465 ms after they stopped talking: …"
so a reply's response time is the last figure, and the stages are how long
it waited to be sure they'd finished (end of turn), speech recognition, the
router, the chat model and the first sentence of speech.
"""
import os
import re
import sqlite3
import statistics
import time
from datetime import datetime

import bot
import transcripts
from sttbench import distance, words

DATABASE = os.environ.get("DATABASE", "")
SPOKE = re.compile(r"Spoke in (\d+) ms, (\d+) ms after they stopped talking")
LLM = re.compile(r" - LLM (\d+) ms:")
HEARD = re.compile(r" - Heard .*?(?:after (\d+) ms|silence (\d+) ms), STT (\d+) ms, router (\d+) ms")
DAY = 86_400_000


def db():
    return sqlite3.connect("file:%s?mode=ro" % DATABASE, uri=True, timeout=5)


def pct(values, p):
    if not values:
        return None
    values = sorted(values)
    return values[min(len(values) - 1, int(round(p / 100 * (len(values) - 1))))]


def median(values):
    return int(statistics.median(values)) if values else None


def since(days):
    return 0 if not days else int(time.time() * 1000) - int(days) * DAY


# ---- overview ---------------------------------------------------------------

def overview():
    if not DATABASE or not os.path.isfile(DATABASE):
        return {}
    with db() as c:
        playing = c.execute("SELECT title, author, uri, requester, started_at, duration_ms, radio FROM plays "
                            "WHERE ended_at IS NULL AND started_at > ? ORDER BY started_at DESC LIMIT 1",
                            (int(time.time() * 1000) - DAY,)).fetchone()
        last = c.execute("SELECT session, MIN(heard_at), MAX(heard_at), COUNT(*), "
                         "SUM(outcome IS NOT NULL AND outcome != 'none'), COUNT(DISTINCT user_id) "
                         "FROM voice_lines WHERE echo = 0 AND session = (SELECT MAX(session) FROM voice_lines)").fetchone()
        plays = c.execute("SELECT COUNT(*) FROM plays WHERE started_at BETWEEN ? AND ?",
                          (last[1] or 0, (last[2] or 0) + 300_000)).fetchone()[0] if last and last[0] else 0
        week = c.execute("SELECT COUNT(*), COUNT(DISTINCT requester_id) FROM plays WHERE started_at > ?",
                         (int(time.time() * 1000) - 7 * DAY,)).fetchone()
    out = {"week_plays": week[0], "week_requesters": week[1]}
    if playing:
        out["playing"] = dict(zip(("title", "author", "uri", "requester", "started", "duration_ms", "radio"), playing))
    if last and last[0]:
        out["last_session"] = {"start": last[1], "end": last[2], "lines": last[3], "for_bot": last[4],
                               "people": last[5], "plays": plays}
    return out


def warnings(hours=24):
    """Warnings and errors the bot logged lately: how many, and the newest few."""
    end = int(time.time() * 1000)
    try:
        got = bot.logs(bot.BOT, levels=("WARN", "ERROR"), start_ms=end - hours * 3600_000, end_ms=end, limit=200)
        return {"count": len(got["entries"]), "recent": got["entries"][:6], "source": got["source"]}
    except Exception as e:
        return {"count": None, "error": type(e).__name__}


# ---- metrics ----------------------------------------------------------------

def metrics(days=30):
    start = since(days)
    out = {"days": days, "sessions": [], "plays_by_day": [], "top_songs": [], "requesters": [], "speech": []}
    if not DATABASE or not os.path.isfile(DATABASE):
        return out
    with db() as c:
        rows = c.execute("SELECT session, heard_at, outcome, stt_ms, router_ms, user_id FROM voice_lines "
                         "WHERE echo = 0 AND session IS NOT NULL AND heard_at >= ? ORDER BY heard_at", (start,)).fetchall()
        sessions = {}
        for session, at, outcome, stt, router, user in rows:
            s = sessions.setdefault(session, {"id": session, "start": at, "end": at, "lines": 0, "for_bot": 0,
                                              "commands": 0, "stt": [], "router": [], "people": set()})
            s["end"] = at
            s["lines"] += 1
            s["for_bot"] += bool(outcome and outcome != "none")
            s["commands"] += bool(outcome and outcome not in ("none", "chat"))
            s["people"].add(user)
            if stt is not None:
                s["stt"].append(stt)
            if router is not None:
                s["router"].append(router)
        for s in sessions.values():
            s["plays"] = c.execute("SELECT COUNT(*) FROM plays WHERE started_at BETWEEN ? AND ?",
                                   (s["start"], s["end"] + 300_000)).fetchone()[0]
        out["plays_by_day"] = c.execute(
            "SELECT date(started_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*), SUM(radio) FROM plays "
            "WHERE started_at >= ? GROUP BY day ORDER BY day", (start,)).fetchall()
        out["top_songs"] = c.execute(
            "SELECT title, COUNT(*) AS n, SUM(ended IN ('stopped', 'replaced') AND duration_ms > 0 AND played_ms < duration_ms * 0.5), "
            "MAX(started_at), MAX(uri) FROM plays WHERE radio = 0 AND started_at >= ? GROUP BY COALESCE(uri, title) "
            "ORDER BY n DESC, MAX(started_at) DESC LIMIT 15", (start,)).fetchall()
        out["requesters"] = c.execute(
            "SELECT COALESCE(requester, '?'), COUNT(*) AS n, ROUND(SUM(COALESCE(played_ms, 0)) / 60000.0) FROM plays "
            "WHERE started_at >= ? GROUP BY requester_id ORDER BY n DESC LIMIT 12", (start,)).fetchall()
        out["speech"] = speech(c, start)
    timings(sessions, start)
    for s in sorted(sessions.values(), key=lambda s: s["start"]):
        s.update(stt=median(s["stt"]), router=median(s["router"]), people=len(s["people"]))
        out["sessions"].append(s)
    out["stages"] = stages(sessions.values())
    return out


def timings(sessions, start):
    """Adds each session's reply timings from the logs. Leaves them out if Loki can't be reached."""
    for s in sessions.values():
        s.update(response=[], llm=[], tts=[], turn=[])
    if not sessions:
        return
    first = min(s["start"] for s in sessions.values()) - 60_000
    try:
        found = bot.loki_lines('{container_name="%s"} |~ "after they stopped talking|LLM [0-9]+ ms:|Heard .* -> "' % bot.BOT,
                               max(first, start), int(time.time() * 1000))
    except Exception:
        for s in sessions.values():
            s["timings"] = False
        return
    spans = sorted(sessions.values(), key=lambda s: s["start"])
    for ts, line in found:
        s = next((s for s in spans if s["start"] - 60_000 <= ts <= s["end"] + 120_000), None)
        if s is None:
            continue
        m = SPOKE.search(line)
        if m:
            s["tts"].append(int(m.group(1)))
            s["response"].append(int(m.group(2)))
            continue
        m = LLM.search(line)
        if m and int(m.group(1)) >= 20:
            s["llm"].append(int(m.group(1)))
            continue
        m = HEARD.search(line)
        if m:
            s["turn"].append(int(m.group(1) or m.group(2)))
            s.setdefault("bot_stt", []).append(int(m.group(3)))
            s.setdefault("bot_router", []).append(int(m.group(4)))


def stages(sessions):
    """Medians of each step of a reply, over the sessions in the range, and of the whole."""
    pool = {k: [] for k in ("turn", "bot_stt", "bot_router", "llm", "tts", "response")}
    for s in sessions:
        for k in pool:
            pool[k] += s.get(k, [])
        for k in ("bot_stt", "bot_router", "turn", "llm", "tts"):
            s.pop(k, None)
        responses = s.pop("response", [])
        s.update(replies=len(responses), p50=pct(responses, 50), p90=pct(responses, 90))
    return {"end_of_turn": median(pool["turn"]), "stt": median(pool["bot_stt"]), "router": median(pool["bot_router"]),
            "llm": median(pool["llm"]), "tts": median(pool["tts"]), "response_p50": pct(pool["response"], 50),
            "response_p90": pct(pool["response"], 90), "replies": len(pool["response"])}


def speech(c, start):
    """Speech recognition per person: how much they said, how quickly it was heard, and how often it was wrong."""
    people = {}
    for user, name, n, ms, audio, empty in c.execute(
            "SELECT user_id, MAX(name), COUNT(*), AVG(stt_ms), SUM(audio_ms), SUM(text = '') FROM voice_lines "
            "WHERE echo = 0 AND heard_at >= ? GROUP BY user_id", (start,)):
        people[user] = {"name": name, "lines": n, "stt_ms": int(ms or 0), "minutes": round((audio or 0) / 60000, 1),
                        "heard_nothing": empty, "reviewed": 0, "wrong": 0, "errors": 0, "words": 0}
    for user, heard, said in c.execute(
            "SELECT l.user_id, l.text, r.text FROM voice_lines l JOIN reference_transcripts r ON r.line_id = l.id "
            "WHERE r.model = 'human' AND l.heard_at >= ?", (start,)):
        p = people.get(user)
        if p is None:
            continue
        a, b = words(heard or ""), words(said or "")
        d = distance(a, b)
        p["reviewed"] += 1
        p["wrong"] += d > 0
        p["errors"] += d
        p["words"] += len(b)
    for p in people.values():
        p["wer"] = round(100 * p["errors"] / p["words"], 1) if p["words"] else None
    return sorted(people.values(), key=lambda p: -p["lines"])


def review_progress():
    """How far the transcript review has got, over every recorded session."""
    total = reviewed = 0
    for s in transcripts.sessions():
        total += s["clips"]
        reviewed += s["reviewed"]
    return {"clips": total, "reviewed": reviewed}
