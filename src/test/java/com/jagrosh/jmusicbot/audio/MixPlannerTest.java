package com.jagrosh.jmusicbot.audio;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public class MixPlannerTest
{
    private static MixPlanner.Item<String> yt(String artist, String song)
    {
        return new MixPlanner.Item<>("t", artist + song, artist + " - " + song, artist, MixPlanner.YOUTUBE);
    }

    private static MixPlanner.Item<String> fm(String artist, String song)
    {
        return MixPlanner.lastFm(new LastFm.Song(artist, song));
    }

    @Test
    public void takesTurnsWithOneSongPerArtist()
    {
        List<MixPlanner.Item<String>> youtube = new ArrayList<>(), lastfm = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            youtube.add(yt("Seed Artist", "Hit " + i)); // YouTube's radio: mostly the seed's own artist
        for (int i = 0; i < 6; i++)
            youtube.add(yt("YT Artist " + i, "Song"));
        for (int i = 0; i < 6; i++)
            lastfm.add(fm("FM Artist " + i, "Song"));
        List<MixPlanner.Item<String>> mix = MixPlanner.merge(youtube, lastfm, Set.of(), Set.of(), "seed artist", 10, new Random(1));
        assertEquals(10, mix.size());
        Set<String> artists = new HashSet<>();
        for (MixPlanner.Item<String> i : mix)
            assertTrue("one per artist: " + i.artist(), artists.add(i.artist()));
        assertEquals(5, mix.stream().filter(i -> i.source().equals(MixPlanner.LASTFM)).count());
        // turns alternate, YouTube first
        for (int k = 0; k < mix.size(); k++)
            assertEquals(k % 2 == 0 ? MixPlanner.YOUTUBE : MixPlanner.LASTFM, mix.get(k).source());
    }

    @Test
    public void leavesOutRecentSongsAndDuplicatesAcrossSources()
    {
        List<MixPlanner.Item<String>> youtube = List.of(yt("Oasis", "Wonderwall (Official Video)"), yt("Blur", "Song 2"));
        List<MixPlanner.Item<String>> lastfm = List.of(fm("Oasis", "Wonderwall"), fm("Pulp", "Common People"));
        List<MixPlanner.Item<String>> mix = MixPlanner.merge(youtube, lastfm, Set.of(), Set.of(Variety.key("Blur - Song 2", "Blur")),
                "", 10, new Random(2));
        List<String> titles = mix.stream().map(MixPlanner.Item::title).toList();
        assertEquals(2, mix.size());
        assertTrue(titles.contains("Pulp - Common People"));
        assertEquals(1, titles.stream().filter(t -> t.toLowerCase().contains("wonderwall")).count());
    }

    @Test
    public void youtubeAloneWhenLastFmHasNothing()
    {
        List<MixPlanner.Item<String>> youtube = new ArrayList<>();
        for (int i = 0; i < 12; i++)
            youtube.add(yt("Artist " + i, "Song"));
        assertEquals(10, MixPlanner.merge(youtube, List.of(), Set.of(), Set.of(), "", 10, new Random(3)).size());
    }

    @Test
    public void readsLastFmAnswers()
    {
        JSONObject similar = new JSONObject("{\"similartracks\":{\"track\":[{\"name\":\"Song 2\",\"artist\":{\"name\":\"Blur\"}},"
                + "{\"name\":\"\",\"artist\":{\"name\":\"X\"}}]}}");
        assertEquals(List.of(new LastFm.Song("Blur", "Song 2")), LastFm.songs(similar, "similartracks"));
        JSONObject top = new JSONObject("{\"toptracks\":{\"track\":[{\"name\":\"Common People\",\"artist\":{\"name\":\"Pulp\"}}]}}");
        assertEquals("Pulp - Common People", LastFm.songs(top, "toptracks").get(0).query());
        assertTrue(LastFm.songs(null, "toptracks").isEmpty());
    }
}
