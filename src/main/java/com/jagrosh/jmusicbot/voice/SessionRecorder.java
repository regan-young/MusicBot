/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.voice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a ?chat session on disk, so speech recognition can be measured and
 * trained on what people here really say: every clip that was sent for
 * transcription, what came back, and a timestamped transcript of the session.
 *
 * One folder per session, named for when and where it started:
 * <pre>
 * 2026-11-12_203105_WannaBET_General/
 *   session.json       when, where, the speech model, and who spoke
 *   transcript.txt     the session to read: "[20:31:09] Devon: ..."
 *   transcript.jsonl   the same for programs, one JSON object per line
 *   clips/203109-412_Devon_289943634567233537.wav
 * </pre>
 * transcript.jsonl has a "heard" line for every clip (the file, the speaker,
 * the text the speech model gave), a "routed" line for what the bot made of
 * it, and a "bot" line for each sentence the bot spoke. Clips are exactly what
 * the speech model was sent: 16 kHz mono 16-bit WAV, named for when the
 * speaker stopped talking.
 *
 * Writing happens on its own thread, so a slow disk never holds up a reply,
 * and a failure to write is logged and otherwise ignored.
 */
final class SessionRecorder
{
    private static final Logger LOG = LoggerFactory.getLogger(SessionRecorder.class);
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss", Locale.ROOT);
    private static final DateTimeFormatter CLIP = DateTimeFormatter.ofPattern("HHmmss-SSS", Locale.ROOT);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final Path folder, clips;
    private final ZoneId zone;
    private final String sttModel;
    private final JSONObject session;
    // user id -> what's known about that speaker, in the order they first spoke
    private final Map<Long, Speaker> speakers = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "voice-recorder");
        t.setDaemon(true);
        return t;
    });

    private static final class Speaker
    {
        String username;
        final TreeSet<String> names = new TreeSet<>();
        int clips;
        long audioMs;
    }

    private SessionRecorder(Path folder, ZoneId zone, String sttModel, JSONObject session)
    {
        this.folder = folder;
        this.clips = folder.resolve("clips");
        this.zone = zone;
        this.sttModel = sttModel;
        this.session = session;
    }

    /**
     * Starts recording a session in a new folder under root.
     * @return the recorder, or null if recording is off (no root) or the folder can't be made
     */
    static SessionRecorder start(String root, String guild, String channel, ZoneId zone, String sttModel)
    {
        return start(root, guild, channel, zone, sttModel, System.currentTimeMillis());
    }

    static SessionRecorder start(String root, String guild, String channel, ZoneId zone, String sttModel, long now)
    {
        if (root == null || root.isBlank())
            return null;
        try
        {
            Path folder = Path.of(root).resolve(FOLDER.format(at(now, zone)) + "_" + safe(guild) + "_" + safe(channel));
            Files.createDirectories(folder.resolve("clips"));
            JSONObject session = new JSONObject()
                    .put("started", STAMP.format(at(now, zone)))
                    .put("guild", guild)
                    .put("channel", channel)
                    .put("timezone", zone.getId())
                    .put("stt_model", sttModel)
                    .put("clip_format", "WAV, 16 kHz, mono, 16-bit: what the speech model was sent");
            SessionRecorder recorder = new SessionRecorder(folder, zone, sttModel, session);
            recorder.writer.execute(() -> recorder.write("session.json", session.toString(2) + "\n", false));
            LOG.info("Recording this session to {}", folder);
            return recorder;
        }
        catch (IOException | RuntimeException e)
        {
            LOG.warn("Not recording this session: {}", e.toString());
            return null;
        }
    }

    /**
     * Saves a clip and what the speech model made of it.
     * @param heardAt when the speaker stopped talking
     * @param name the speaker's name in this server
     * @param text the transcript, empty if the speech model heard nothing
     * @param echo whether it was taken for the bot's own voice through their mic
     * @return the clip's path within the session folder, to pass to {@link #routed}
     */
    String heard(long heardAt, long userId, String username, String name, byte[] wav, String text, long audioMs, long sttMs,
            boolean echo)
    {
        String clip = "clips/" + CLIP.format(at(heardAt, zone)) + "_" + safe(name) + "_" + userId + ".wav";
        JSONObject entry = new JSONObject()
                .put("type", "heard")
                .put("time", STAMP.format(at(heardAt, zone)))
                .put("clip", clip)
                .put("user_id", String.valueOf(userId))
                .put("username", username)
                .put("name", name)
                .put("audio_ms", audioMs)
                .put("text", text)
                .put("stt_model", sttModel)
                .put("stt_ms", sttMs);
        if (echo)
            entry.put("echo", true);
        String line = text.isBlank() ? null
                : "[" + CLOCK.format(at(heardAt, zone)) + "] " + name + ": " + text + (echo ? "  (the bot's own voice, ignored)" : "");
        writer.execute(() ->
        {
            try
            {
                Files.write(folder.resolve(clip), wav);
            }
            catch (IOException e)
            {
                LOG.warn("Couldn't save {}: {}", clip, e.toString());
                return;
            }
            Speaker s = speakers.computeIfAbsent(userId, id -> new Speaker());
            s.username = username;
            s.names.add(name);
            s.clips++;
            s.audioMs += audioMs;
            write("transcript.jsonl", entry + "\n", true);
            if (line != null)
                write("transcript.txt", line + "\n", true);
        });
        return clip;
    }

    /**
     * Notes what the bot made of a clip.
     * @param outcome "none" if it wasn't for the bot, "chat", or the command that ran
     * @param why how it was decided, when it wasn't the router: "reaction", "name only"; or empty
     * @param route the router's answer, or null if it wasn't asked
     */
    void routed(String clip, String outcome, String args, String why, VoiceRouter.Route route, boolean named, boolean followUp,
            long routerMs)
    {
        long now = System.currentTimeMillis();
        JSONObject entry = new JSONObject()
                .put("type", "routed")
                .put("time", STAMP.format(at(now, zone)))
                .put("clip", clip)
                .put("for_bot", !outcome.equals("none"))
                .put("outcome", outcome)
                .put("named", named)
                .put("follow_up", followUp);
        if (args != null && !args.isEmpty())
            entry.put("args", args);
        if (why != null && !why.isEmpty())
            entry.put("why", why);
        if (route != null)
        {
            entry.put("router_ms", routerMs);
            if (route.scored)
                entry.put("confidence", Math.round(route.confidence * 1000) / 1000.0);
        }
        String line = outcome.equals("none") || outcome.equals("chat") ? null
                : "[" + CLOCK.format(at(now, zone)) + "]   -> " + outcome + (args == null || args.isEmpty() ? "" : " [" + args + "]");
        writer.execute(() ->
        {
            write("transcript.jsonl", entry + "\n", true);
            if (line != null)
                write("transcript.txt", line + "\n", true);
        });
    }

    /** Notes a sentence the bot spoke. */
    void bot(String name, String text)
    {
        long now = System.currentTimeMillis();
        JSONObject entry = new JSONObject().put("type", "bot").put("time", STAMP.format(at(now, zone))).put("text", text);
        writer.execute(() ->
        {
            write("transcript.jsonl", entry + "\n", true);
            write("transcript.txt", "[" + CLOCK.format(at(now, zone)) + "] " + name + ": " + text + "\n", true);
        });
    }

    /** Ends the session: session.json gets when it ended and who spoke. Waits a moment for the writing to finish. */
    void close()
    {
        long now = System.currentTimeMillis();
        writer.execute(() ->
        {
            JSONObject who = new JSONObject();
            speakers.forEach((id, s) -> who.put(String.valueOf(id), new JSONObject()
                    .put("username", s.username).put("names", s.names).put("clips", s.clips).put("audio_ms", s.audioMs)));
            session.put("ended", STAMP.format(at(now, zone))).put("speakers", who);
            write("session.json", session.toString(2) + "\n", false);
        });
        writer.shutdown();
        try
        {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    Path folder()
    {
        return folder;
    }

    private void write(String file, String text, boolean append)
    {
        try
        {
            if (append)
                Files.writeString(folder.resolve(file), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            else
                Files.writeString(folder.resolve(file), text, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            LOG.warn("Couldn't write {}: {}", file, e.toString());
        }
    }

    private static ZonedDateTime at(long millis, ZoneId zone)
    {
        return Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.MILLIS).atZone(zone);
    }

    /** A name as part of a file name: letters, digits, dots and dashes, the rest dropped. */
    static String safe(String name)
    {
        String s = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N}.-]+", "-").replaceAll("^[-.]+|[-.]+$", "");
        if (s.length() > 40)
            s = s.substring(0, 40);
        return s.isEmpty() ? "unknown" : s;
    }
}
