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
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What each person wants the bot to say when they join its voice channel,
 * set with ?greeting or by asking her. Kept in a JSON file next to
 * serversettings.json: user id -> {"name": ..., "text": ..., "request": bool,
 * "recent": [...]}.
 *
 * Either exact words, said as they are ({name} becomes their display name),
 * or a request ("a different fun fact each time", "roast me"), set with a
 * "style: " prefix: then a fresh welcome is written from it every time they
 * join, and the last few are kept so it doesn't repeat itself.
 */
public class Greetings
{
    private static final Logger LOG = LoggerFactory.getLogger(Greetings.class);
    public static final int MAX_LENGTH = 300;
    /** Put before a greeting to make it a request that a fresh welcome is written from. */
    public static final String REQUEST = "style:";
    static final int RECENT = 5;

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

    /** Their greeting (the exact words, or the request), or empty for the usual one. */
    public synchronized String get(long userId)
    {
        JSONObject p = people.optJSONObject(Long.toString(userId));
        return p == null ? "" : p.optString("text", "");
    }

    /** Whether their greeting is a request to write a fresh welcome from, rather than words to say. */
    public synchronized boolean isRequest(long userId)
    {
        JSONObject p = people.optJSONObject(Long.toString(userId));
        return p != null && p.optBoolean("request", false);
    }

    /** Their greeting as people set it: the words, or "style: " and the request; empty for the usual one. */
    public synchronized String describe(long userId)
    {
        String text = get(userId);
        return text.isEmpty() || !isRequest(userId) ? text : REQUEST + " " + text;
    }

    /** The last welcomes written for them from their request, oldest first. */
    public synchronized List<String> recent(long userId)
    {
        JSONObject p = people.optJSONObject(Long.toString(userId));
        List<String> out = new ArrayList<>();
        JSONArray arr = p == null ? null : p.optJSONArray("recent");
        if (arr != null)
            for (int i = 0; i < arr.length(); i++)
                out.add(arr.optString(i));
        return out;
    }

    /** Notes a welcome written for them, so the next one is different. */
    public synchronized void said(long userId, String welcome)
    {
        JSONObject p = people.optJSONObject(Long.toString(userId));
        if (p == null)
            return;
        JSONArray arr = p.optJSONArray("recent");
        if (arr == null)
            p.put("recent", arr = new JSONArray());
        arr.put(welcome);
        while (arr.length() > RECENT)
            arr.remove(0);
        JsonFile.save(file, people.toString(2), LOG);
    }

    /**
     * Sets their greeting; empty text goes back to the usual one, and text
     * starting "style:" is a request. Returns what was kept, as
     * {@link #describe} shows it.
     */
    public synchronized String set(long userId, String name, String text)
    {
        String kept = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        boolean request = kept.regionMatches(true, 0, REQUEST, 0, REQUEST.length());
        if (request)
            kept = kept.substring(REQUEST.length()).trim();
        kept = shorten(kept);
        if (kept.isEmpty())
            people.remove(Long.toString(userId));
        else
        {
            JSONObject p = new JSONObject().put("name", name).put("text", kept);
            if (request)
                p.put("request", true);
            people.put(Long.toString(userId), p);
        }
        JsonFile.save(file, people.toString(2), LOG);
        return kept.isEmpty() || !request ? kept : REQUEST + " " + kept;
    }

    /** Cut to MAX_LENGTH at the end of a sentence, or else a word, so it isn't left mid-word. */
    static String shorten(String text)
    {
        if (text.length() <= MAX_LENGTH)
            return text;
        String head = text.substring(0, MAX_LENGTH);
        int sentence = Math.max(head.lastIndexOf(". "), Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        if (sentence > MAX_LENGTH / 2)
            return head.substring(0, sentence + 1);
        int word = head.lastIndexOf(' ');
        return (word > MAX_LENGTH / 2 ? head.substring(0, word) : head).trim();
    }
}
