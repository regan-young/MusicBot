#!/usr/bin/env python3
"""
Gets a second transcript of every clip in a recorded session from a cloud
speech model, so the corrections page can flag the lines the bot's own model
probably got wrong.

    OPENROUTER_API_KEY=... python3 reference.py <session folder> [model] [--second] [--database gunnabot.db] [--limit N]

Writes <session>/reference.json: {"model": ..., "lines": {clip: text}}, saved
as it goes, so it can be stopped and run again and only asks for what is
missing. With --second it asks a different model and writes reference-2.json:
two cloud models that agree with each other and not with the bot's are a much
surer sign of a mistake than one that disagrees. With --database the transcripts also go into reference_transcripts
under the model's name. sttbench.py is how the default model was chosen.

The clips are people's voices and they leave the house: this sends each one to
OpenRouter and the model's provider.
"""
import json
import os
import sqlite3
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

import sttbench

MODEL = "mistralai/voxtral-mini-transcribe-3-0"
SECOND = "qwen/qwen3-asr-1.7b"


def main():
    args = sys.argv[1:]
    database = args.pop(args.index("--database") + 1) if "--database" in args else ""
    limit = int(args.pop(args.index("--limit") + 1)) if "--limit" in args else 0
    second = "--second" in args
    args = [a for a in args if not a.startswith("--")]
    session, model = args[0].rstrip("/"), (args[1] if len(args) > 1 else SECOND if second else MODEL)
    if not sttbench.KEY:
        sys.exit("OPENROUTER_API_KEY is not set")
    path = os.path.join(session, "reference-2.json" if second else "reference.json")
    data = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
    if data.get("model") != model:
        data = {"model": model, "lines": {}}
    heard = {}
    for raw in open(os.path.join(session, "transcript.jsonl"), encoding="utf-8"):
        try:
            e = json.loads(raw)
        except ValueError:
            continue
        if e.get("type") == "heard" and e.get("clip"):
            heard[e["clip"]] = e
    todo = [c for c in heard if c not in data["lines"] and os.path.isfile(os.path.join(session, c))]
    if limit:
        todo = todo[:limit]
    print("%s: %d clips, %d to ask %s about" % (os.path.basename(session), len(heard), len(todo), model), flush=True)

    def write():
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        os.replace(tmp, path)

    failed, cost, started, fresh = 0, 0.0, time.time(), {}
    with ThreadPoolExecutor(8) as pool:
        for n, (clip, got) in enumerate(zip(todo, pool.map(lambda c: sttbench.attempt(model, os.path.join(session, c)), todo)), 1):
            if "error" in got:
                failed += 1
                if failed <= 3:
                    print("  failed:", clip, got["error"][:200], flush=True)
            else:
                data["lines"][clip] = fresh[clip] = got["text"]
                cost += got.get("cost") or 0
            if n % 200 == 0:
                write()
                print("  %d of %d, $%.3f so far" % (n, len(todo), cost), flush=True)
    write()
    print("done in %d s: %d transcribed, %d failed, $%.3f" % (time.time() - started, len(fresh), failed, cost), flush=True)

    if database and fresh:
        db = sqlite3.connect(database, timeout=30)
        now, kept = int(time.time() * 1000), 0
        for clip, text in fresh.items():
            at = int(datetime.fromisoformat(heard[clip]["time"]).timestamp() * 1000)
            row = db.execute("SELECT id FROM voice_lines WHERE clip = ? AND heard_at BETWEEN ? AND ?", (clip, at - 5, at + 5)).fetchone()
            if row:
                db.execute("INSERT OR REPLACE INTO reference_transcripts (line_id, model, text, created_at) VALUES (?, ?, ?, ?)",
                           (row[0], model, text, now))
                kept += 1
        db.commit()
        db.close()
        print("%d written to reference_transcripts" % kept, flush=True)


if __name__ == "__main__":
    main()
