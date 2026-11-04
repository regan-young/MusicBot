package com.jagrosh.jmusicbot.audio;

import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceMixTest
{
    /** Samples as 16-bit big-endian PCM, the format the players output. */
    private static byte[] pcm(int... samples)
    {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++)
        {
            out[i * 2] = (byte) (samples[i] >> 8);
            out[i * 2 + 1] = (byte) samples[i];
        }
        return out;
    }

    @Test
    public void addsTheSamples()
    {
        assertArrayEquals(pcm(300, -100, 0), AudioHandler.mix(pcm(100, 100, 0), pcm(200, -200, 0)));
    }

    @Test
    public void lowBytesCarryIntoHighBytes()
    {
        // 0x00FF has a low byte that reads as negative if it's sign-extended
        assertArrayEquals(pcm(0x01FE), AudioHandler.mix(pcm(0x00FF), pcm(0x00FF)));
    }

    @Test
    public void peaksHoldAtTheLimitInsteadOfWrapping()
    {
        assertArrayEquals(pcm(Short.MAX_VALUE), AudioHandler.mix(pcm(30000), pcm(30000)));
        assertArrayEquals(pcm(Short.MIN_VALUE), AudioHandler.mix(pcm(-30000), pcm(-30000)));
    }

    @Test
    public void leavesTheInputsAlone()
    {
        byte[] music = pcm(100);
        AudioHandler.mix(music, pcm(200));
        assertArrayEquals(pcm(100), music);
    }
}
