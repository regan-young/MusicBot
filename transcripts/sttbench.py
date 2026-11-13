#!/usr/bin/env python3
"""
Compares cloud speech-to-text models on clips from a recorded session, to pick
the one that labels sessions for the corrections page (see reference.py).

    OPENROUTER_API_KEY=... python3 sttbench.py <session folder> <out.json> [clips] model [model ...]

A model id with a "chat:" prefix is a chat model given the clip as audio input
and asked to transcribe; any other is sent to OpenRouter's transcription
endpoint. Nobody has typed out what was really said, so each model is measured
against the consensus: for each clip, the transcript closest to all the others.
Results are kept in <out.json>, so a rerun only asks for what is missing.

Standard library only.
"""
import base64
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor

API = "https://openrouter.ai/api/v1"
KEY = os.environ.get("OPENROUTER_API_KEY", "")
ASK = ("Transcribe this short clip from a Discord voice chat between friends from New Zealand playing Dota 2. "
       "They sometimes talk to a music bot called Gunnabot. Write exactly the words spoken and nothing else: "
       "no notes, no speaker names, no quotation marks. If there is no speech, reply with a single dash.")


# however a model spells the bot's name, it's the same word
BOT = re.compile(r"\b(?:g[a-z]{1,6}|gonna|gunna)[ -]?bot\b")


# sounds one model writes down and another leaves out
FILLERS = {"um", "umm", "uh", "uhh", "er", "erm", "ah", "hm", "hmm", "mm", "mhm"}


def words(text):
    """
    The words of a transcript, for comparing two of them: no case or
    punctuation, no ums, a stuttered word once, "over-explaining" as one word.
    """
    text = BOT.sub("gunnabot", text.lower()).replace("'", "").replace("’", "")
    text = re.sub(r"(?<=[a-z])-(?=[a-z])", "", text)
    out = []
    for w in re.sub(r"[^a-z0-9 ]+", " ", text).split():
        if w not in FILLERS and (not out or out[-1] != w):
            out.append(w)
    return out


def distance(a, b):
    """Word edit distance."""
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        last, row[0] = row[0], i
        for j, y in enumerate(b, 1):
            last, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, last + (x != y))
    return row[-1]


def post(url, body, headers, timeout=120):
    req = urllib.request.Request(url, data=body, headers=dict(headers, Authorization="Bearer " + KEY))
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def transcribe(model, path):
    """Returns (text, cost in dollars or None)."""
    audio = open(path, "rb").read()
    if model.startswith("chat:"):
        body = {"model": model[5:], "temperature": 0, "usage": {"include": True}, "messages": [{"role": "user", "content": [
            {"type": "text", "text": ASK},
            {"type": "input_audio", "input_audio": {"data": base64.b64encode(audio).decode(), "format": "wav"}}]}]}
        got = post(API + "/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
        text = (got["choices"][0]["message"].get("content") or "").strip()
        return ("" if text in ("-", "—") else text), (got.get("usage") or {}).get("cost")
    edge = "----" + uuid.uuid4().hex
    parts = b"".join(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (edge, k, v)).encode()
                     for k, v in (("model", model), ("language", "en")))
    parts += ("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n"
              "Content-Type: audio/wav\r\n\r\n" % edge).encode() + audio + ("\r\n--%s--\r\n" % edge).encode()
    got = post(API + "/audio/transcriptions", parts, {"Content-Type": "multipart/form-data; boundary=" + edge})
    return (got.get("text") or "").strip(), (got.get("usage") or {}).get("cost")


def attempt(model, path):
    for tries in range(3):
        started = time.time()
        try:
            text, cost = transcribe(model, path)
            return {"text": text, "cost": cost, "ms": int((time.time() - started) * 1000)}
        except urllib.error.HTTPError as e:
            err = "%d %s" % (e.code, e.read()[:300].decode("utf-8", "replace"))
            if e.code not in (429, 500, 502, 503, 504, 524):
                break
        except Exception as e:  # timeouts, resets
            err = repr(e)
        time.sleep(2 + 4 * tries)
    return {"error": err}


def sample(session, count):
    """Half the lines the bot acted on or was named in, half any line long enough to have words in it."""
    heard, order = {}, []
    for raw in open(os.path.join(session, "transcript.jsonl"), encoding="utf-8"):
        e = json.loads(raw)
        if e.get("type") == "heard" and e.get("clip"):
            heard[e["clip"]] = e
            order.append(e)
        elif e.get("type") == "routed" and e.get("clip") in heard:
            heard[e["clip"]]["bot"] = bool(e.get("for_bot") or e.get("named"))
    rng = random.Random(12)
    bot = [e for e in order if e.get("bot")]
    rest = [e for e in order if not e.get("bot") and e.get("audio_ms", 0) >= 1200]
    picked = rng.sample(bot, min(count // 2, len(bot)))
    picked += rng.sample(rest, min(count - len(picked), len(rest)))
    return {e["clip"]: {"heard": e.get("text", ""), "name": e.get("name"), "audio_ms": e.get("audio_ms", 0),
                        "bot": bool(e.get("bot"))} for e in picked}


def main():
    session, out = sys.argv[1], sys.argv[2]
    args = sys.argv[3:]
    count = int(args.pop(0)) if args and args[0].isdigit() else 100
    data = json.load(open(out, encoding="utf-8")) if os.path.exists(out) else {"clips": sample(session, count), "models": {}}
    clips = data["clips"]
    for model in args:
        got = data["models"].setdefault(model, {})
        todo = [c for c in clips if c not in got or "error" in got[c]]
        if not todo:
            continue
        with ThreadPoolExecutor(6) as pool:
            for clip, result in zip(todo, pool.map(lambda c: attempt(model, os.path.join(session, c)), todo)):
                got[clip] = result
        errors = [r["error"] for r in got.values() if "error" in r]
        print("%-46s asked %3d, failed %3d %s" % (model, len(todo), len(errors), errors[0][:160] if errors else ""), flush=True)
        json.dump(data, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    report(data)


def report(data):
    clips, models = data["clips"], dict(data["models"])
    models["(the bot's Parakeet, local)"] = {c: {"text": v["heard"]} for c, v in clips.items()}
    usable = {m: r for m, r in models.items() if sum("text" in x for x in r.values()) >= 0.9 * len(clips)}
    cloud = [m for m in usable if not m.startswith("(")]
    seconds = sum(v["audio_ms"] for v in clips.values()) / 1000
    truth = {}
    for c in clips:
        texts = [words(usable[m][c]["text"]) for m in cloud if "text" in usable[m].get(c, {})]
        if texts:
            truth[c] = min(texts, key=lambda t: sum(distance(t, o) for o in texts))
    rows = []
    for m, r in usable.items():
        err = total = same = n = bot_err = bot_total = 0
        for c, t in truth.items():
            if "text" not in r.get(c, {}):
                continue
            d = distance(words(r[c]["text"]), t)
            err, total, same, n = err + d, total + len(t), same + (d == 0), n + 1
            if clips[c]["bot"]:
                bot_err, bot_total = bot_err + d, bot_total + len(t)
        costs = [x["cost"] for x in r.values() if x.get("cost") is not None]
        ms = sorted(x["ms"] for x in r.values() if "ms" in x)
        rows.append((err / max(total, 1), m, same / max(n, 1), bot_err / max(bot_total, 1),
                     sum(costs) / seconds * 3600 if costs else None, ms[len(ms) // 2] if ms else None))
    print("\n%d clips, %.0f s of audio. Difference from the consensus of %d cloud models, in words:\n" % (len(truth), seconds, len(cloud)))
    print("%-46s %7s %7s %8s %9s %7s" % ("model", "differs", "to bot", "same", "$/hour", "median"))
    for e, m, same, bot, cost, ms in sorted(rows):
        print("%-46s %6.1f%% %6.1f%% %7.0f%% %9s %7s" % (m, 100 * e, 100 * bot, 100 * same,
              "%.3f" % cost if cost is not None else "?", "%d ms" % ms if ms else ""))
    left = [m for m in models if m not in usable]
    if left:
        print("\nleft out (too many failures):", ", ".join(left))


if __name__ == "__main__":
    main()
