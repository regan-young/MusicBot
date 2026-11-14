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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What each person wants the bot to say when they join its voice channel,
 * set with ?greeting or by asking her. Kept in a JSON file next to
 * serversettings.json: user id -> {"name": ..., "text": ...}. The text may
 * hold {name}, which becomes their display name.
 */
public class Greetings
{
    private static final Logger LOG = LoggerFactory.getLogger(Greetings.class);
    public static final int MAX_LENGTH = 150;

    private final Path file;
    private final JSONObject people;

    public Greetings(Path file)
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
                LOG.warn("Could not read {}; starting with no greetings", file, e);
            }
        }
        this.people = loaded;
    }

    /** Their greeting, or empty for the usual one. */
    public synchronized String get(long userId)
    {
        JSONObject p = people.optJSONObject(Long.toString(userId));
        return p == null ? "" : p.optString("text", "");
    }

    /** Sets their greeting; empty text goes back to the usual one. Returns what was kept. */
    public synchronized String set(long userId, String name, String text)
    {
        String kept = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (kept.length() > MAX_LENGTH)
            kept = kept.substring(0, MAX_LENGTH).trim();
        if (kept.isEmpty())
            people.remove(Long.toString(userId));
        else
            people.put(Long.toString(userId), new JSONObject().put("name", name).put("text", kept));
        JsonFile.save(file, people.toString(2), LOG);
        return kept;
    }
}
