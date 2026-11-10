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
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * When each person was last seen being active: their last message and their
 * last voice join, leave or move. Discord's online status needs the privileged
 * presence intent, which the bot doesn't have, so this only knows what it has
 * watched happen since it started recording. Kept in a JSON file next to
 * voice-memory.json.
 */
public class LastSeen
{
    private static final Logger LOG = LoggerFactory.getLogger(LastSeen.class);
    /** Writes for the same person closer together than this wait for the next event. */
    static final long SAVE_EVERY_MS = 60_000;

    /** What someone was last seen doing, and when. */
    public record Seen(long userId, String name, long messageAt, long voiceAt)
    {
        public long latest()
        {
            return Math.max(messageAt, voiceAt);
        }
    }

    private final Path file;
    private final JSONObject people; // user id -> {"name", "message", "voice"} (epoch millis)

    public LastSeen(Path file)
    {
        this.file = file;
        JSONObject loaded = new JSONObject();
        if (Files.exists(file))
        {
            try
            {
                loaded = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            }
            catch (Exception e)
            {
                LOG.warn("Could not read {}; starting with nobody seen", file, e);
            }
        }
        this.people = loaded;
    }

    public synchronized void message(long userId, String name, long now)
    {
        record(userId, name, "message", now);
    }

    public synchronized void voice(long userId, String name, long now)
    {
        record(userId, name, "voice", now);
    }

    private void record(long userId, String name, String kind, long now)
    {
        String key = Long.toString(userId);
        JSONObject person = people.optJSONObject(key);
        if (person == null)
        {
            person = new JSONObject();
            people.put(key, person);
        }
        long previous = Math.max(person.optLong("message", 0), person.optLong("voice", 0));
        person.put("name", name).put(kind, now);
        if (now - previous >= SAVE_EVERY_MS)
            save();
    }

    public synchronized Seen byId(long userId)
    {
        JSONObject person = people.optJSONObject(Long.toString(userId));
        return person == null ? null : toSeen(Long.toString(userId), person);
    }

    /** The most recently active person whose name contains the query, ignoring case; null if none. */
    public synchronized Seen byName(String query)
    {
        String wanted = query.trim().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty())
            return null;
        Seen best = null;
        for (String key : people.keySet())
        {
            JSONObject person = people.getJSONObject(key);
            if (!person.optString("name", "").toLowerCase(Locale.ROOT).contains(wanted))
                continue;
            Seen seen = toSeen(key, person);
            if (best == null || seen.latest() > best.latest())
                best = seen;
        }
        return best;
    }

    /** Forgets a person, e.g. when they ask to be forgotten. */
    public synchronized boolean forget(long userId)
    {
        boolean had = people.remove(Long.toString(userId)) != null;
        if (had)
            save();
        return had;
    }

    /** "just now", "5 minutes ago", "2 days ago". */
    public static String ago(long millis)
    {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes < 1)
            return "just now";
        if (minutes < 60)
            return unit(minutes, "minute");
        if (minutes < 60 * 24)
            return unit(minutes / 60, "hour");
        return unit(minutes / (60 * 24), "day");
    }

    private static String unit(long n, String word)
    {
        return n + " " + word + (n == 1 ? "" : "s") + " ago";
    }

    private static Seen toSeen(String key, JSONObject person)
    {
        return new Seen(Long.parseLong(key), person.optString("name", "Someone"),
                person.optLong("message", 0), person.optLong("voice", 0));
    }

    private void save()
    {
        try
        {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, people.toString(2), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            LOG.warn("Could not save {}", file, e);
        }
    }
}
