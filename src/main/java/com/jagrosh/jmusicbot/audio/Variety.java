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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/**
 * Keeps picked music from going round in circles. YouTube's mix for a song
 * leads with the same handful of hits every time, so queuing the top of it
 * replays what was on yesterday and fills the queue with one artist. This
 * leaves out what's been played lately or is already queued, allows only a
 * couple of songs per artist, and takes a random spread of what's left in
 * the mix's own order.
 */
public final class Variety
{
    private Variety() {}

    /** At most this many songs by one artist in one pick. */
    public static final int PER_ARTIST = 2;

    /**
     * A song's identity across uploads: "Oasis - Wonderwall (Official Video)"
     * and "Wonderwall [Remastered]" by Oasis both become "oasis wonderwall"
     * once the artist is known.
     */
    public static String key(String title, String author)
    {
        String t = title == null ? "" : title;
        int dash = t.indexOf(" - ");
        String song = clean(dash > 0 ? t.substring(dash + 3) : t);
        String a = artist(title, author);
        return a.isEmpty() ? song : a + " " + song;
    }

    /** Who a track is by: the part before " - " in the title, or else the channel without VEVO or "- Topic". */
    public static String artist(String title, String author)
    {
        String t = title == null ? "" : title;
        int dash = t.indexOf(" - ");
        String a = dash > 0 ? t.substring(0, dash) : author == null ? "" : author;
        a = a.replaceAll("(?i)vevo$|\\s*-\\s*topic$|\\bofficial\\b", "");
        // channel names run words together: "HarryStylesVEVO"
        a = a.replaceAll("(?<=\\p{Ll})(?=\\p{Lu})", " ");
        // "Harry Styles & Lizzo" counts as Harry Styles
        a = a.split("(?i)\\s*(,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\band\\b)\\s*")[0];
        return clean(a);
    }

    private static String clean(String s)
    {
        if (s == null)
            return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)|\\[[^\\]]*\\]", " ")
                .replaceAll("\\b(official|music|lyric|lyrics|video|audio|visualizer|hd|4k|remaster(ed)?)\\b", " ")
                .replaceAll("[^\\p{L}\\p{N} ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Picks up to wanted items, leaving out recent ones (by id or by song) and
     * duplicates, with at most PER_ARTIST per artist, chosen at random and
     * kept in their original order. If everything's been played lately, it
     * picks from those rather than nothing.
     * @param id an item's YouTube id or URL, or null
     * @param title an item's title; author its channel
     */
    public static <T> List<T> pick(List<T> items, Function<T, String> id, Function<T, String> title, Function<T, String> author,
            Collection<String> recentIds, Collection<String> recentKeys, int wanted, Random random)
    {
        List<Integer> fresh = new ArrayList<>(), stale = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++)
        {
            T item = items.get(i);
            String k = key(title.apply(item), author.apply(item));
            if (!seen.add(k))
                continue;
            String itemId = id.apply(item);
            boolean recent = (itemId != null && recentIds.contains(itemId)) || recentKeys.contains(k);
            (recent ? stale : fresh).add(i);
        }
        List<Integer> chosen = choose(items, fresh, title, author, wanted, random, new HashMap<>());
        if (chosen.isEmpty())
            chosen = choose(items, stale, title, author, wanted, random, new HashMap<>());
        Collections.sort(chosen);
        List<T> out = new ArrayList<>();
        for (int i : chosen)
            out.add(items.get(i));
        return out;
    }

    private static <T> List<Integer> choose(List<T> items, List<Integer> pool, Function<T, String> title,
            Function<T, String> author, int wanted, Random random, Map<String, Integer> perArtist)
    {
        List<Integer> order = new ArrayList<>(pool);
        Collections.shuffle(order, random);
        List<Integer> chosen = new ArrayList<>();
        for (int i : order)
        {
            if (chosen.size() >= wanted)
                break;
            String a = artist(title.apply(items.get(i)), author.apply(items.get(i)));
            int n = perArtist.getOrDefault(a, 0);
            if (!a.isEmpty() && n >= PER_ARTIST)
                continue;
            perArtist.put(a, n + 1);
            chosen.add(i);
        }
        return chosen;
    }
}
