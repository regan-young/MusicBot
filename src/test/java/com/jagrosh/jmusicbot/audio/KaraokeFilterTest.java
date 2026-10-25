package com.jagrosh.jmusicbot.audio;

import org.junit.Test;

import static org.junit.Assert.*;

public class KaraokeFilterTest
{
    @Test
    public void centredSoundIsCancelled()
    {
        float[] left = {0.5f, -0.25f, 0.8f};
        float[] right = {0.5f, -0.25f, 0.8f};
        KaraokeFilter.removeCentre(left, right, 0, 3);
        assertArrayEquals(new float[3], left, 0f);
        assertArrayEquals(new float[3], right, 0f);
    }

    @Test
    public void sideSoundIsKeptInBothChannels()
    {
        float[] left = {0.5f, 0.9f};
        float[] right = {-0.5f, 0.9f};
        KaraokeFilter.removeCentre(left, right, 1, 1);
        assertEquals(0.5f, left[0], 0f); // outside the range, untouched
        assertEquals(0f, left[1], 0f);
        assertEquals(0f, right[1], 0f);
        KaraokeFilter.removeCentre(left, right, 0, 1);
        assertEquals(0.5f, left[0], 0f);
        assertEquals(0.5f, right[0], 0f);
    }
}
