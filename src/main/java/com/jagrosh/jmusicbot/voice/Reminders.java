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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reminders people ask for by voice or text ("remind us in 20 minutes to
 * start the game"). Saved to a JSON file so they survive a restart; one
 * that came due while the bot was down fires shortly after it starts.
 */
public class Reminders
{
    private static final Logger LOG = LoggerFactory.getLogger(Reminders.class);
    static final long MAX_DELAY_MS = 7 * 24 * 3600_000L;

    /** A reminder: who asked, where, what for, and when. */
    public static class Reminder
    {
        public final String id;
        public final long guildId, channelId, userId, dueAt;
        public final String name, text;

        Reminder(String id, long guildId, long channelId, long userId, String name, String text, long dueAt)
        {
            this.id = id;
            this.guildId = guildId;
            this.channelId = channelId;
            this.userId = userId;
            this.name = name;
            this.text = text;
            this.dueAt = dueAt;
        }

        JSONObject toJson()
        {
            return new JSONObject().put("id", id).put("guild", guildId).put("channel", channelId).put("user", userId)
                    .put("name", name).put("text", text).put("due", dueAt);
        }

        static Reminder fromJson(JSONObject o)
        {
            return new Reminder(o.getString("id"), o.getLong("guild"), o.getLong("channel"), o.getLong("user"),
                    o.optString("name"), o.getString("text"), o.getLong("due"));
        }
    }

    private final Path file;
    private final ScheduledExecutorService timer;
    private final Consumer<Reminder> onDue;
    private final List<Reminder> pending = new ArrayList<>();

    /** @param onDue called (on the timer thread) when a reminder comes due */
    public Reminders(Path file, ScheduledExecutorService timer, Consumer<Reminder> onDue)
    {
        this.file = file;
        this.timer = timer;
        this.onDue = onDue;
        if (Files.exists(file))
        {
            try
            {
                JSONArray saved = new JSONArray(Files.readString(file, StandardCharsets.UTF_8));
                for (int i = 0; i < saved.length(); i++)
                {
                    Reminder r = Reminder.fromJson(saved.getJSONObject(i));
                    pending.add(r);
                    // overdue ones wait a little for Discord to connect after a restart
                    schedule(r, Math.max(30_000, r.dueAt - System.currentTimeMillis()));
                }
                LOG.info("Loaded {} reminders", pending.size());
            }
            catch (Exception e)
            {
                LOG.warn("Could not read {}", file, e);
            }
        }
    }

    /** Sets a reminder; minutes is clamped to between 10 seconds and a week. */
    public synchronized Reminder add(long guildId, long channelId, long userId, String name, String text, double minutes)
    {
        long delay = Math.max(10_000, Math.min(MAX_DELAY_MS, Math.round(minutes * 60_000)));
        Reminder r = new Reminder(UUID.randomUUID().toString(), guildId, channelId, userId, name, text.trim(),
                System.currentTimeMillis() + delay);
        pending.add(r);
        save();
        schedule(r, delay);
        LOG.info("Reminder for {} in {} s: {}", name, delay / 1000, r.text);
        return r;
    }

    /** Cancels someone's reminders in a guild. Returns how many. */
    public synchronized int cancel(long guildId, long userId)
    {
        int before = pending.size();
        pending.removeIf(r -> r.guildId == guildId && r.userId == userId);
        save();
        return before - pending.size();
    }

    private void schedule(Reminder r, long delayMs)
    {
        timer.schedule(() ->
        {
            synchronized (this)
            {
                // cancelled meanwhile
                if (!pending.remove(r))
                    return;
                save();
            }
            onDue.accept(r);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void save()
    {
        try
        {
            JSONArray out = new JSONArray();
            pending.forEach(r -> out.put(r.toJson()));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, out.toString(2), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e)
        {
            LOG.warn("Could not save {}", file, e);
        }
    }
}
