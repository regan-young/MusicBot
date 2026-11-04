package com.jagrosh.jmusicbot;

import com.jagrosh.jmusicbot.commands.dj.SkiptoCmd;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class SkiptoTest
{
    private static final List<String> SONGS = List.of(
            "Luck Be Good Some Band",
            "Good Luck, Babe! Chappell Roan",
            "Dreams (2004 Remaster) Fleetwood Mac",
            "1979 The Smashing Pumpkins");

    @Test
    public void byPosition()
    {
        assertEquals(0, SkiptoCmd.findPosition(SONGS, "1"));
        assertEquals(3, SkiptoCmd.findPosition(SONGS, " 4 "));
        assertEquals(2, SkiptoCmd.findPosition(SONGS, "number 3"));
        assertEquals(1, SkiptoCmd.findPosition(SONGS, "#2"));
    }

    @Test
    public void spokenNumbers()
    {
        assertEquals(2, SkiptoCmd.findPosition(SONGS, "number three"));
        assertEquals(1, SkiptoCmd.findPosition(SONGS, "two"));
        assertEquals(1, SkiptoCmd.findPosition(SONGS, "song 2"));
        assertEquals(3, SkiptoCmd.findPosition(SONGS, "track four"));
    }

    @Test
    public void byName()
    {
        assertEquals(2, SkiptoCmd.findPosition(SONGS, "dreams"));
        assertEquals(2, SkiptoCmd.findPosition(SONGS, "dream"));
        assertEquals(2, SkiptoCmd.findPosition(SONGS, "Fleetwood Mac"));
        assertEquals(1, SkiptoCmd.findPosition(SONGS, "good luck babe"));
        assertEquals(2, SkiptoCmd.findPosition(SONGS, "mac dreams"));
    }

    @Test
    public void howPeopleSayIt()
    {
        assertEquals(2, SkiptoCmd.findPosition(SONGS, "the fleetwood mac one"));
        assertEquals(1, SkiptoCmd.findPosition(SONGS, "the chappell roan song"));
        // filler is still a name when it's all there is
        assertEquals(1, SkiptoCmd.findPosition(List.of("Dreams", "One Metallica"), "one"));
    }

    @Test
    public void wordsNotPartsOfWords()
    {
        List<String> songs = List.of("Train Drops of Jupiter", "Rain The Beatles", "Dreams (2005 Remaster) Fleetwood Mac");
        assertEquals(1, SkiptoCmd.findPosition(songs, "rain"));
        // out of range, and not a word in any title
        assertEquals(-1, SkiptoCmd.findPosition(songs, "5"));
    }

    @Test
    public void accentsAndApostrophes()
    {
        List<String> songs = List.of("Halo Beyoncé", "Don't Stop Me Now Queen");
        assertEquals(0, SkiptoCmd.findPosition(songs, "beyonce"));
        assertEquals(1, SkiptoCmd.findPosition(songs, "dont stop"));
        assertEquals(1, SkiptoCmd.findPosition(songs, "don't stop"));
    }

    @Test
    public void theExactPhraseBeatsWordsInAnyOrder()
    {
        assertEquals(1, SkiptoCmd.findPosition(SONGS, "good luck"));
    }

    @Test
    public void aNumberOutOfRangeIsTriedAsAName()
    {
        assertEquals(3, SkiptoCmd.findPosition(SONGS, "1979"));
    }

    @Test
    public void aTitleThatLooksLikeAPosition()
    {
        List<String> songs = List.of("A", "B", "C", "Song 2 Blur");
        assertEquals(3, SkiptoCmd.findPosition(songs, "song 2"));
    }

    @Test
    public void noSuchSong()
    {
        assertEquals(-1, SkiptoCmd.findPosition(SONGS, "5"));
        assertEquals(-1, SkiptoCmd.findPosition(SONGS, "Taylor Swift"));
        assertEquals(-1, SkiptoCmd.findPosition(SONGS, ""));
        assertEquals(-1, SkiptoCmd.findPosition(SONGS, "!!!"));
        assertEquals(-1, SkiptoCmd.findPosition(List.of(), "1"));
    }
}
