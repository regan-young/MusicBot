# Gunnabot

A Discord music bot you can talk to. It is a fork of [JMusicBot](https://github.com/jagrosh/MusicBot) that runs in one Discord server: it plays music as JMusicBot does, and with `?chat` it listens in the voice channel, answers out loud, and takes requests in plain speech.

This repository is the bot one server runs, kept public so it can be read. It is not packaged for others to install, and its defaults assume a home server. The upstream project is the place to go for a general-purpose music bot.

## What it does

**Music** (from JMusicBot, with additions)

- `?play`, `?search`, `?queue`, `?skip`, `?nowplaying`, playlists, a DJ role, and the rest of JMusicBot's commands. The most used also exist as slash commands, and the now-playing message has buttons.
- `?radio` plays live stations: New Zealand favourites by name, or any station worldwide.
- `?mix` queues several songs like a song or artist.
- `?skipto` jumps to a queued song by position or name. `?karaoke` strips the vocals.
- YouTube is played through `yt-dlp`, which must be on the machine.

**Voice** (`?chat`)

- Listens to everyone in the voice channel and works out which lines are said to it, so nobody has to use its name every time.
- Runs music requests said aloud ("put on some Six60", "turn it down a bit", "skip to Dreams").
- Chats, and talks over the music instead of pausing it.
- Looks things up on the web in the background and comes back with the answer when there is a gap in the conversation.
- Sets reminders, remembers lasting facts people tell it, and answers questions about this week's supermarket specials.
- Can change its own code: people on the owner's whitelist ask, a sidecar running Claude Code makes and tests the change, and the bot restarts to deploy it.

**Text**

- @mentioning the bot, or replying to it, uses the same brain and answers in text.

## How a spoken line is handled

```
Discord audio, one stream per person
  -> VoiceListener    cuts each person's audio into utterances (Smart Turn decides when they've finished)
  -> VoiceApi         speech-to-text on a local speech server
  -> VoiceRouter      is this line for the bot, and which command is it?
       -> a command   runs the real command (?play, ?volume, ...) as the speaker
       -> chat        LlmClient writes the reply, or picks an action (research, remind, play, ...)
  -> VoiceApi         text-to-speech, mixed over the music by AudioHandler
```

The router is the part that makes the bot feel right or wrong, so it is worth knowing how it decides:

- A **decision model** (`routerdecisionmodel`, Gemini Decision 1 on OpenRouter's Decisions API) answers two questions with probabilities: is the line for the bot, and which command is it. The bot acts when the combined score passes a threshold, which is higher for a line that neither names the bot nor follows something it just said.
- A small **chat model** (`routermodel`, Gemma 3 12B) is then asked only for what a command needs spelled out: the song, the station or the volume. It also takes over the whole job when the decision model is not set or fails.
- Saying the bot's name counts as talking to it, unless the decision model is confident it was not ("going bot" is a Dota lane).
- While the router decides, the chat reply is already being written, and is thrown away if the line turns out to be a command or not for the bot.

## Configuration

Every setting is documented where its default lives, in [`src/main/resources/reference.conf`](src/main/resources/reference.conf). The bot reads `config.txt` from its working directory. Beyond JMusicBot's own settings, the groups are:

| Settings | What they turn on |
|---|---|
| `voiceapi`, `voicesttmodel`, `voicettsmodel`, `voicettsvoice` | Speech. An OpenAI-compatible speech server; without it there is no `?chat` or `?say`. |
| `llmapi`, `llmkey`, `llmmodel`, `llmprompt`, `llmmaxtokens` | The chat model and the bot's persona. Without a key, `?chat` only takes simple spoken commands. |
| `routerapi`, `routerkey`, `routermodel`, `routerdecisionmodel` | The router. Without `routerapi`, the bot only answers to its name. |
| `smartturnmodel` | The end-of-turn model, so the bot does not cut people off. |
| `researchmodel` | The model for background web research (needs OpenRouter). |
| `voicerecordings` | A folder to save `?chat` sessions in. See below. |
| `nsfwllm*`, `crudellmprompt` | Explicit mode (age-restricted channels only) and crude mode. |
| `coderapi`, `coderkey` | The self-editing sidecar in [`coder/`](coder). |
| `riceapi` | The supermarket specials tracker. |
| `database`, `databasespeechdays` | The history database, and how long what people said is kept in it. See below. |
| `timezone`, `postchannels`, `musicgain`, `mixsongs`, `ytdlppath` | Clock for reminders, where the bot may post, music level under speech, mix size, and where `yt-dlp` is. |

## Files the bot keeps

All in its working directory, next to `config.txt`:

| File | Contents |
|---|---|
| `serversettings.json` | Per-server settings (JMusicBot). |
| `gunnabot.db` | The history database: what was played, said and asked for. See below. |
| `voice-memory.json` | Facts people have told the bot about themselves. "Forget me" removes a person's. |
| `voice-reminders.json` | Reminders that have not come due. |
| `last-seen.json` | When each person last typed or was in voice. |
| `voice-feedback.jsonl` | Lines the bot answered that someone then said were not for it ("not you, bot"). |
| `coders.json`, `restart.json` | The self-edit whitelist, and a note that brings the bot back to its voice channel after it deploys itself. |

## The history database

The JSON files above hold what the bot knows now. `gunnabot.db`, an SQLite file, holds what happened, so it can be asked about. It is written off the threads that play and listen, and the bot runs without it if it cannot be opened.

| Table | A row for | Used for |
|---|---|---|
| `plays` | Each track started: who asked, when, how it ended and how far in. | "Play that again" after a restart, questions like "what was on last Friday", and picking songs people here like. |
| `voice_lines`, `bot_lines` | Each line heard in `?chat` with its transcript, timings, the router's score and the outcome; and each sentence the bot said. | Measuring the router and the speech recognition over time. |
| `corrections` | A line the bot answered that someone said was not for it. | The router's error rate, and new benchmark cases. |
| `reference_transcripts` | A better transcript of a line from another model. | Measuring speech recognition per person. Nothing writes to it yet. |
| `code_requests` | Each change or question sent to the self-edit sidecar, and how it went. | A record of who asked for what. |

Three views summarise them: `play_counts`, `router_daily` (lines heard, taken for the bot, corrected, and average timings per day) and `stt_by_person`.

```
sqlite3 gunnabot.db "SELECT day, lines, for_bot, corrections, avg_router_ms FROM router_daily ORDER BY day DESC LIMIT 14"
```

A song counts as cut short when it was skipped or stopped in its first half. What people said is personal: `databasespeechdays` drops it after that many days, and asking the bot to forget you removes your lines and takes your name off what you played.

## Session recordings

With `voicerecordings` set, each `?chat` session is saved so speech recognition can be measured and improved for the people in the server. **It records everyone in the voice channel, so tell them.** One folder per session:

```
2026-11-12_203105_ServerName_ChannelName/
  session.json       when, where, the speech model, and who spoke
  transcript.txt     the session to read: [20:31:09] Devon: ...
  transcript.jsonl   the same for programs, one JSON object per line
  clips/203109-412_Devon_289943634567233537.wav
```

Each clip is the audio exactly as the speech model received it (16 kHz mono WAV), named for when the speaker stopped talking, their name and their Discord ID. In `transcript.jsonl`, a `heard` line gives each clip's file, speaker and transcript; a `routed` line says what the bot made of it; a `bot` line is a sentence the bot spoke. The bot never deletes or moves recordings.

## Building, testing and deploying

- Java 25 and Maven. `mvn -B verify` builds the jar and runs the tests.
- [`Dockerfile`](Dockerfile) builds the image, and [`docker-compose.yml`](docker-compose.yml) runs it with the working directory mounted at `/musicbot`.
- A push to `master` runs the tests on GitHub, then a self-hosted runner rebuilds the image and replaces the container. A broken `master` is a broken bot.

### Measuring the router

`RouterBench` (in `src/test`) runs a router over a file of labelled lines and reports how often the bot would have done the right thing: how many lines it butted in on, missed, or got the command wrong for, and how long the router took. It calls the real models, so it needs an OpenRouter key and costs a little.

```
mvn -B test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
OPENROUTER_API_KEY=... java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" \
    com.jagrosh.jmusicbot.voice.RouterBench cases.jsonl google/gemma-3-12b-it google/gemini-decision-1
```

The case format is described at the top of `RouterBench.java`. Cases come from real sessions (the recordings and `voice-feedback.jsonl` are good sources), so they are kept out of this repository.

## Notes for working on the code

[`CLAUDE.md`](CLAUDE.md) says where things are and the conventions the code follows. It is written for the coding agent that makes the bot's self-edits, and it is the quickest way in for a person too.

## Credits and licence

Gunnabot is built on [JMusicBot](https://github.com/jagrosh/MusicBot) by John Grosh, with [JDA](https://github.com/discord-jda/JDA) and [lavaplayer](https://github.com/lavalink-devs/lavaplayer). End-of-turn detection uses [Smart Turn](https://github.com/pipecat-ai/smart-turn). Like JMusicBot, it is released under the [Apache 2.0 licence](LICENSE).
