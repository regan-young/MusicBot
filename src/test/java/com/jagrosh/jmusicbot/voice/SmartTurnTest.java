package com.jagrosh.jmusicbot.voice;

import org.junit.Test;

import static org.junit.Assert.*;

public class SmartTurnTest
{
    /** 1.5 s of a 220 Hz tone with a 1 kHz overtone, rising in volume, as 16-bit little-endian PCM. */
    private static byte[] tone()
    {
        byte[] pcm = new byte[24000 * 2];
        for (int n = 0; n < 24000; n++)
        {
            double x = 8000 * (0.5 + 0.5 * n / 24000.0)
                    * (Math.sin(2 * Math.PI * 220 * n / 16000) + 0.3 * Math.sin(2 * Math.PI * 1000 * n / 16000));
            short s = (short) Math.round(x);
            pcm[2 * n] = (byte) s;
            pcm[2 * n + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    @Test
    public void featuresMatchWhisperFeatureExtractor()
    {
        // expected values from transformers' WhisperFeatureExtractor(chunk_length=8)
        // on the same signal, as pipecat's Smart Turn inference uses it
        float[] f = SmartTurn.features(tone());
        assertEquals(SmartTurn.MELS * SmartTurn.FRAMES, f.length);
        double mean = 0;
        for (float v : f)
            mean += v;
        assertEquals(-0.07586493343114853, mean / f.length, 1e-5);
        assertEquals(-0.1319899559020996, at(f, 0, 0), 1e-4);
        assertEquals(1.7814137935638428, at(f, 5, 700), 1e-4);
        assertEquals(0.41133618354797363, at(f, 12, 750), 1e-4);
        assertEquals(0.6942183971405029, at(f, 30, 799), 1e-4);
        assertEquals(-0.1319899559020996, at(f, 79, 790), 1e-4);
    }

    private static float at(float[] f, int mel, int frame)
    {
        return f[mel * SmartTurn.FRAMES + frame];
    }

    @Test
    public void melFiltersCoverTheSpectrum()
    {
        double[][] filters = SmartTurn.melFilters();
        assertEquals(SmartTurn.BINS, filters.length);
        for (int m = 0; m < SmartTurn.MELS; m++)
        {
            double sum = 0;
            for (double[] bin : filters)
                sum += bin[m];
            assertTrue("mel filter " + m + " is empty", sum > 0);
        }
    }

    @Test
    public void silenceIsPaddedToThePauseLength()
    {
        byte[] pcm = new byte[3200]; // 100 ms
        // speech ended at 60 ms; 200 ms of pause is 60 + 200 ms of audio
        assertEquals((1920 + 6400), VoiceListener.withSilence(pcm, 1920, 200).length);
        // Discord's own trailing frames already cover it
        assertEquals(3200, VoiceListener.withSilence(pcm, 3000, 0).length);
    }
}
