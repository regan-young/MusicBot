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
package com.jagrosh.jmusicbot.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What was playing and queued in a guild, kept on disk so "resume" can bring
 * it back after the bot leaves, is kicked, or restarts. The queue itself only
 * lives in memory. One file per guild, {@code queue-<guild id>.json}, beside
 * config.txt.
 */
public final class SavedQueue
{
    private static final Logger LOG = LoggerFactory.getLogger(SavedQueue.class);
    /** Songs after the current one that are kept; a huge playlist is cut here. */
    static final int MAX_UPCOMING = 200;

    public record Entry(String uri, String title, long requesterId, String requester, String avatar) {}

    /**
     * @param position how far into {@code current} it was, in ms
     */
    public record Snapshot(long savedAt, Entry current, long position, List<Entry> upcoming)
    {
        /** The same queue, ignoring when it was saved (so unchanged queues aren't rewritten). */
        boolean sameAs(Snapshot o)
        {
            return o != null && current.equals(o.current) && position / 1000 == o.position / 1000 && upcoming.equals(o.upcoming);
        }
    }

    private SavedQueue() {}

    static Path file(long guildId)
    {
        return Path.of("queue-" + guildId + ".json");
    }

    static String toJson(Snapshot s)
    {
        JSONArray up = new JSONArray();
        s.upcoming().forEach(e -> up.put(entry(e)));
        return new JSONObject()
                .put("savedAt", s.savedAt())
                .put("current", entry(s.current()))
                .put("position", s.position())
                .put("upcoming", up)
                .toString(1);
    }

    static Snapshot fromJson(String json)
    {
        JSONObject o = new JSONObject(json);
        List<Entry> up = new ArrayList<>();
        JSONArray arr = o.optJSONArray("upcoming");
        if (arr != null)
            for (int i = 0; i < arr.length(); i++)
                up.add(entry(arr.getJSONObject(i)));
        return new Snapshot(o.optLong("savedAt"), entry(o.getJSONObject("current")), o.optLong("position"), up);
    }

    private static JSONObject entry(Entry e)
    {
        return new JSONObject().put("uri", e.uri()).put("title", e.title()).put("requesterId", e.requesterId())
                .put("requester", e.requester() == null ? JSONObject.NULL : e.requester())
                .put("avatar", e.avatar() == null ? JSONObject.NULL : e.avatar());
    }

    private static Entry entry(JSONObject o)
    {
        return new Entry(o.getString("uri"), o.optString("title", o.getString("uri")), o.optLong("requesterId"),
                o.isNull("requester") ? null : o.optString("requester", null),
                o.isNull("avatar") ? null : o.optString("avatar", null));
    }

    /** The saved queue for a guild, or null if there is none (or it can't be read). */
    public static Snapshot load(long guildId)
    {
        Path f = file(guildId);
        if (!Files.exists(f))
            return null;
        try
        {
            return fromJson(Files.readString(f, StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            LOG.warn("Could not read the saved queue {}", f, e);
            return null;
        }
    }

    static void save(long guildId, Snapshot s)
    {
        Path f = file(guildId);
        try
        {
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(s), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            LOG.warn("Could not save the queue to {}", f, e);
        }
    }

    static void delete(long guildId)
    {
        try
        {
            Files.deleteIfExists(file(guildId));
        }
        catch (IOException e)
        {
            LOG.warn("Could not delete the saved queue for {}", guildId, e);
        }
    }

    /** How "resume" says what it brought back. */
    public static String describe(Snapshot s)
    {
        int more = s.upcoming().size();
        return s.current().title() + (more == 0 ? "" : more == 1 ? ", and one more after it" : ", and " + more + " more after it");
    }
}
