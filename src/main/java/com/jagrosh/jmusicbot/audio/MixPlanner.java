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
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Puts a mix together from two places: YouTube's radio for the seed song,
 * and Last.fm's similar tracks and similar artists. The two take turns, and
 * the result is kept varied: one song per artist, nothing played this past
 * week or already queued, and past the obvious picks at the top of each list
 * (YouTube's radio always leads with the same few hits).
 */
public final class MixPlanner
{
    private MixPlanner() {}

    public static final String YOUTUBE = "YouTube", LASTFM = "Last.fm";
    /** The first few of a long list are the obvious picks everyone gets; these are left out. */
    static final int SKIP_TOP = 3, SKIP_MIN = 15;
    /** How many similar artists a deeper track is taken from. */
    static final int SIMILAR_ARTISTS = 6;

    /**
     * One song for the mix. {@code track} is set for YouTube's (already
     * loaded) and null for Last.fm's, which are searched on YouTube when
     * queued. {@code title}/{@code author} are what tell songs and artists
     * apart; for Last.fm, "Artist - Title" and the artist.
     */
    public record Item<T>(T track, String id, String title, String author, String source)
    {
        String artist()
        {
            return Variety.artist(title, author);
        }

        String key()
        {
            return Variety.key(title, author);
        }
    }

    public static <T> Item<T> lastFm(LastFm.Song s)
    {
        return new Item<>(null, null, s.query(), s.artist(), LASTFM);
    }

    /** The list without its first few entries when it's long enough to spare them. */
    static <T> List<T> deeper(List<T> list)
    {
        return list.size() >= SKIP_MIN ? list.subList(SKIP_TOP, list.size()) : list;
    }

    /**
     * Last.fm's candidates for a seed: similar tracks past the most obvious,
     * then one deeper track (not their biggest) from each of several similar
     * artists, shuffled. Empty if Last.fm is off or knows nothing about it.
     */
    public static List<LastFm.Song> lastFmCandidates(LastFm fm, LastFm.Song seed, Random random)
    {
        return lastFmCandidates(fm, seed, true, random);
    }

    /**
     * @param sure false when the seed's artist is only a guess (the channel of
     *             a bare title): then Last.fm must know the song itself, or
     *             nothing is used, so a wrong artist can't steer the mix
     */
    public static List<LastFm.Song> lastFmCandidates(LastFm fm, LastFm.Song seed, boolean sure, Random random)
    {
        if (fm == null || !fm.isEnabled() || seed == null)
            return List.of();
        CompletableFuture<List<LastFm.Song>> similar = CompletableFuture.supplyAsync(() -> fm.similarTracks(seed.artist(), seed.title(), 60));
        if (!sure && get(similar).isEmpty())
            return List.of();
        List<String> artists = new ArrayList<>(deeper(fm.similarArtists(seed.artist(), 40)));
        Collections.shuffle(artists, random);
        List<CompletableFuture<List<LastFm.Song>>> tops = new ArrayList<>();
        for (String a : artists.subList(0, Math.min(SIMILAR_ARTISTS, artists.size())))
            tops.add(CompletableFuture.supplyAsync(() -> fm.topTracks(a, 15)));
        List<LastFm.Song> out = new ArrayList<>(deeper(get(similar)));
        for (CompletableFuture<List<LastFm.Song>> f : tops)
        {
            List<LastFm.Song> top = get(f);
            // past their one or two biggest songs, when they have enough
            List<LastFm.Song> pool = top.size() > 4 ? top.subList(2, top.size()) : top;
            if (!pool.isEmpty())
                out.add(pool.get(random.nextInt(pool.size())));
        }
        Collections.shuffle(out, random);
        return out;
    }

    private static List<LastFm.Song> get(CompletableFuture<List<LastFm.Song>> f)
    {
        try
        {
            return f.get(10, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            return List.of();
        }
    }

    /**
     * The mix: YouTube and Last.fm taking turns (each shuffled, the obvious
     * top of YouTube's radio left out), one song per artist (the seed's
     * artist at most once), nothing in recentIds/recentKeys, up to
     * {@code wanted}. If that's too few, artists may come up twice; if nothing
     * new is left at all, recent songs are allowed back.
     */
    public static <T> List<Item<T>> merge(List<Item<T>> youtube, List<Item<T>> lastfm, Collection<String> recentIds,
            Collection<String> recentKeys, String seedArtist, int wanted, Random random)
    {
        List<Item<T>> yt = new ArrayList<>(deeper(youtube)), fm = new ArrayList<>(lastfm);
        Collections.shuffle(yt, random);
        Collections.shuffle(fm, random);
        List<Item<T>> out = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Map<String, Integer> perArtist = new HashMap<>();
        for (int cap = 1; cap <= 2 && out.size() < wanted; cap++)
            take(yt, fm, out, keys, perArtist, recentIds, recentKeys, seedArtist, wanted, cap, false);
        // only when there's nothing new at all: better a repeat than silence
        if (out.isEmpty())
            take(yt, fm, out, keys, perArtist, recentIds, recentKeys, seedArtist, wanted, 2, true);
        return out;
    }

    private static <T> void take(List<Item<T>> yt, List<Item<T>> fm, List<Item<T>> out, Set<String> keys,
            Map<String, Integer> perArtist, Collection<String> recentIds, Collection<String> recentKeys, String seedArtist,
            int wanted, int cap, boolean allowRecent)
    {
        int i = 0, j = 0;
        boolean ytTurn = true;
        while (out.size() < wanted && (i < yt.size() || j < fm.size()))
        {
            boolean fromYt = ytTurn ? i < yt.size() : j >= fm.size();
            Item<T> item = fromYt ? yt.get(i++) : fm.get(j++);
            // a song passed over doesn't use up that source's turn
            String key = item.key(), artist = item.artist();
            if (keys.contains(key))
                continue;
            if (!allowRecent && ((item.id() != null && recentIds.contains(item.id())) || recentKeys.contains(key)))
                continue;
            int limit = !artist.isEmpty() && artist.equals(seedArtist) ? 1 : cap;
            if (!artist.isEmpty() && perArtist.getOrDefault(artist, 0) >= limit)
                continue;
            keys.add(key);
            perArtist.merge(artist, 1, Integer::sum);
            out.add(item);
            ytTurn = !fromYt;
        }
    }
}
