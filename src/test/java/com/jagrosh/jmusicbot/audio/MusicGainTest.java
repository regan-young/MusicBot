package com.jagrosh.jmusicbot.audio;

import org.junit.Test;

import static org.junit.Assert.*;

public class MusicGainTest
{
    @Test
    public void scalesVolumeByGain()
    {
        assertEquals(20, AudioHandler.scaledVolume(100, 20));
        assertEquals(30, AudioHandler.scaledVolume(150, 20));
        assertEquals(10, AudioHandler.scaledVolume(50, 20));
        assertEquals(0, AudioHandler.scaledVolume(0, 20));
    }

    @Test
    public void fullGainLeavesVolumeAlone()
    {
        assertEquals(100, AudioHandler.scaledVolume(100, 100));
        assertEquals(73, AudioHandler.scaledVolume(73, 100));
    }
}
