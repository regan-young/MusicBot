package com.jagrosh.jmusicbot.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class PostChannelsTest
{
    @Test
    public void emptyListAllowsEverything()
    {
        assertTrue(PostChannels.allowed(List.of(), "general"));
        assertTrue(PostChannels.allowed(null, "general"));
    }

    @Test
    public void matchesByNameIgnoringCase()
    {
        List<String> names = List.of("music", "Bats");
        assertTrue(PostChannels.allowed(names, "MUSIC"));
        assertTrue(PostChannels.allowed(names, "bats"));
        assertFalse(PostChannels.allowed(names, "general"));
    }

    @Test
    public void refusalListsChannels()
    {
        assertEquals("I can only post in #music, #bats.", PostChannels.refusal(List.of("music", "bats")));
    }
}
