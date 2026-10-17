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
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the bot remembers about people across sessions: a few short facts
 * per Discord user ("Regan supports the Blues"), which the LLM adds when
 * someone tells it something lasting. Kept in a JSON file next to
 * serversettings.json; "forget me" removes a person's entry.
 */
public class VoiceMemory
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceMemory.class);
    static final int MAX_NOTES = 20;

    private final Path file;
    private final JSONObject people; // user id -> {"name": ..., "notes": [...]}

    public VoiceMemory(Path file)
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
                LOG.warn("Could not read {}; starting with no memories", file, e);
            }
        }
        this.people = loaded;
    }

    /** Remembers a fact about someone; the oldest goes once they have MAX_NOTES. */
    public synchronized void remember(long userId, String name, String fact)
    {
        String note = fact.trim();
        if (note.isEmpty())
            return;
        JSONObject person = people.optJSONObject(Long.toString(userId));
        if (person == null)
        {
            person = new JSONObject().put("notes", new JSONArray());
            people.put(Long.toString(userId), person);
        }
        person.put("name", name);
        JSONArray notes = person.getJSONArray("notes");
        for (int i = 0; i < notes.length(); i++)
            if (notes.getString(i).toLowerCase(Locale.ROOT).equals(note.toLowerCase(Locale.ROOT)))
                return;
        notes.put(note);
        while (notes.length() > MAX_NOTES)
            notes.remove(0);
        save();
    }

    /** Forgets everything about someone. Returns whether there was anything. */
    public synchronized boolean forget(long userId)
    {
        boolean had = people.remove(Long.toString(userId)) != null;
        if (had)
            save();
        return had;
    }

    public synchronized List<String> notes(long userId)
    {
        List<String> out = new ArrayList<>();
        JSONObject person = people.optJSONObject(Long.toString(userId));
        if (person != null)
            person.getJSONArray("notes").forEach(n -> out.add(n.toString()));
        return out;
    }

    /**
     * A line for the LLM's context about the given people, e.g. "Regan: supports
     * the Blues; hates Nickelback. Sam: ...", or empty if it knows nothing.
     */
    public synchronized String describe(Collection<Long> userIds)
    {
        StringBuilder sb = new StringBuilder();
        for (long id : userIds)
        {
            JSONObject person = people.optJSONObject(Long.toString(id));
            if (person == null || person.getJSONArray("notes").isEmpty())
                continue;
            List<String> notes = new ArrayList<>();
            person.getJSONArray("notes").forEach(n -> notes.add(n.toString()));
            sb.append(sb.length() == 0 ? "" : " ").append(person.optString("name", "Someone")).append(": ")
              .append(String.join("; ", notes)).append('.');
        }
        return sb.toString();
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
