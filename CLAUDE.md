# Gunnabot

A JMusicBot fork running in one Discord server (Java 25, JDA 6, lavaplayer, Maven). It plays music, and with `?chat` it listens in voice, answers out loud and takes requests; @mentioning it in a text channel uses the same brain.

## Build and test

- `mvn -B verify` builds the shaded jar and runs the JUnit 4 tests. It must pass before a change is done.
- Pushing to `master` deploys: GitHub Actions runs the tests, then the self-hosted runner rebuilds the Docker image and replaces the container. A broken master means a broken bot.

## Where things are

- Commands: `src/main/java/com/jagrosh/jmusicbot/commands/` (by permission level: `music`, `dj`, `admin`, `owner`), registered in `JMusicBot.createCommandClient`. Slash commands are a curated list in `InteractionListener.SLASH`, and run the prefix commands through `InteractionCommandEvent`.
- Voice and chat: `voice/`. `VoiceManager` runs `?chat` sessions and text chat; `VoiceRouter` (a small LLM) decides whether a spoken line is for the bot and which command it is; `LlmClient` is the conversational LLM, whose JSON decision format (`ACTIONS`, `DECISION_SCHEMA`, `parseDecision`) the code depends on; `Researcher` does background web research; `Coder` is self-editing (whitelist, the coder sidecar, restarts).
- Config: every key needs a default in `src/main/resources/reference.conf` (with a comment explaining it) and a field and getter in `BotConfig`. The live config.txt is not in the repo.
- Radio stations: `RadioCmd` and `RadioMetadata`.
- `src/main/resources/about.txt` is the bot's description of itself, added to its chat persona. When a change alters what the bot can do or how it behaves, update it to match, and keep it short (it's sent with every message).

## Conventions

- Match the surrounding code: Allman braces, 4-space indents, and comments that explain why rather than what.
- Libraries must be built for JDA 6.
- The bot speaks through text-to-speech, so anything it says out loud is short, plain sentences with no markdown, emoji or URLs (`LlmClient.forSpeech`).
- Don't block JDA's event threads with network calls; the voice code uses its own executors.
- Add tests for logic that can be tested without Discord (parsing, routing, queue behaviour), in `src/test/java`.
- `coder/` (the sidecar that runs Claude Code) and `.github/` are maintained by hand. Don't change them in a self-edit.
