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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the bot remembers across sessions, kept in a JSON file next to
 * serversettings.json:
 * <ul>
 * <li>a few short facts per Discord user ("Regan supports the Blues"), which
 *     the LLM adds when someone tells it something lasting; "forget me"
 *     removes a person's entry;</li>
 * <li>shared memories of the group under the key "group": running jokes,
 *     things that happened, facts about someone said by someone else, and
 *     the moments the bot picks out when a ?chat session ends.</li>
 * </ul>
 * The dashboard can edit the file while the bot runs, so it's read again
 * whenever it changes on disk.
 */
public class VoiceMemory
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceMemory.class);
    static final int MAX_NOTES = 20;
    static final int MAX_GROUP = 40;
    /** Shared memories the LLM sees each turn: the most recent ones. */
    static final int GROUP_IN_CONTEXT = 20;
    static final String GROUP = "group";

    private final Path file;
    private JSONObject people; // user id -> {"name": ..., "notes": [...]}, and "group" -> {"notes": [...]}
    private long loadedAt = -1; // the file's modified time when it was last read or written

    public VoiceMemory(Path file)
    {
        this.file = file;
        this.people = new JSONObject();
        refresh();
    }

    /** Reads the file again if something else (the dashboard) changed it. */
    private void refresh()
    {
        long modified = modified();
        if (modified == loadedAt)
            return;
        loadedAt = modified;
        if (modified < 0)
            return;
        try
        {
            people = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
        }
        catch (Exception e)
        {
            LOG.warn("Could not read {}; keeping the memories already loaded", file, e);
        }
    }

    private long modified()
    {
        try
        {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    /** Remembers a fact about someone; the oldest goes once they have MAX_NOTES. */
    public synchronized void remember(long userId, String name, String fact)
    {
        refresh();
        JSONObject person = people.optJSONObject(Long.toString(userId));
        if (person == null)
            person = new JSONObject().put("notes", new JSONArray());
        person.put("name", name);
        if (add(person, fact, MAX_NOTES))
        {
            people.put(Long.toString(userId), person);
            save();
        }
    }

    /** Remembers something about the group as a whole; the oldest goes once there are MAX_GROUP. */
    public synchronized void rememberGroup(String fact)
    {
        refresh();
        JSONObject group = people.optJSONObject(GROUP);
        if (group == null)
            group = new JSONObject().put("name", "Everyone").put("notes", new JSONArray());
        if (add(group, fact, MAX_GROUP))
        {
            people.put(GROUP, group);
            save();
        }
    }

    /** Adds a note unless it's empty or already there. Returns whether it was added. */
    private static boolean add(JSONObject entry, String fact, int max)
    {
        String note = fact == null ? "" : fact.trim();
        if (note.isEmpty())
            return false;
        JSONArray notes = entry.getJSONArray("notes");
        for (int i = 0; i < notes.length(); i++)
            if (notes.getString(i).toLowerCase(Locale.ROOT).equals(note.toLowerCase(Locale.ROOT)))
                return false;
        notes.put(note);
        while (notes.length() > max)
            notes.remove(0);
        return true;
    }

    /** Forgets everything about someone. Returns whether there was anything. */
    public synchronized boolean forget(long userId)
    {
        refresh();
        boolean had = people.remove(Long.toString(userId)) != null;
        if (had)
            save();
        return had;
    }

    public synchronized List<String> notes(long userId)
    {
        refresh();
        return notes(people.optJSONObject(Long.toString(userId)));
    }

    public synchronized List<String> groupNotes()
    {
        refresh();
        return notes(people.optJSONObject(GROUP));
    }

    private static List<String> notes(JSONObject entry)
    {
        List<String> out = new ArrayList<>();
        if (entry != null)
            entry.optJSONArray("notes", new JSONArray()).forEach(n -> out.add(n.toString()));
        return out;
    }

    /**
     * A line for the LLM's context about the given people, e.g. "Regan: supports
     * the Blues; hates Nickelback. Sam: ...", or empty if it knows nothing.
     */
    public synchronized String describe(Collection<Long> userIds)
    {
        refresh();
        StringBuilder sb = new StringBuilder();
        for (long id : userIds)
        {
            JSONObject person = people.optJSONObject(Long.toString(id));
            List<String> notes = notes(person);
            if (notes.isEmpty())
                continue;
            sb.append(sb.length() == 0 ? "" : " ").append(person.optString("name", "Someone")).append(": ")
              .append(String.join("; ", notes)).append('.');
        }
        return sb.toString();
    }

    /** The most recent shared memories, oldest first, joined for the LLM's context; empty if none. */
    public synchronized String describeGroup()
    {
        List<String> notes = groupNotes();
        List<String> recent = notes.subList(Math.max(0, notes.size() - GROUP_IN_CONTEXT), notes.size());
        StringBuilder sb = new StringBuilder();
        for (String n : recent)
            sb.append(sb.length() == 0 ? "" : " ").append(n).append(n.matches(".*[.!?]$") ? "" : ".");
        return sb.toString();
    }

    private void save()
    {
        JsonFile.save(file, people.toString(2), LOG);
        loadedAt = modified();
    }
}
