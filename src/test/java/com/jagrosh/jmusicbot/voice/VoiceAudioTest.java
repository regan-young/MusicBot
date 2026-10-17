package com.jagrosh.jmusicbot.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceAudioTest
{
    /** 20 ms of 48 kHz stereo big-endian PCM with every sample set to the given values. */
    private static byte[] frame(short left, short right)
    {
        ByteBuffer b = ByteBuffer.allocate(960 * 4).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < 960; i++)
            b.putShort(left).putShort(right);
        return b.array();
    }

    @Test
    public void downmixesAndDecimates()
    {
        byte[] out = VoiceAudio.toMono16k(frame((short) 1000, (short) 3000));
        assertEquals(320 * 2, out.length); // 20 ms at 16 kHz mono
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 320; i++)
            assertEquals(2000, b.getShort());
        assertEquals(20, VoiceAudio.durationMs(out.length));
    }

    @Test
    public void handlesNegativeSamples()
    {
        byte[] out = VoiceAudio.toMono16k(frame((short) -32768, (short) -32768));
        assertEquals(-32768, ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    @Test
    public void rmsSeparatesSilenceFromSpeech()
    {
        assertEquals(0, VoiceAudio.rms(VoiceAudio.toMono16k(frame((short) 0, (short) 0))), 0.001);
        assertTrue(VoiceAudio.rms(VoiceAudio.toMono16k(frame((short) 4000, (short) 4000))) >= VoiceListener.SPEECH_RMS);
    }

    @Test
    public void wavHeader()
    {
        byte[] pcm = new byte[640];
        byte[] wav = VoiceAudio.toWav(pcm);
        assertEquals(44 + 640, wav.length);
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(wav, 0, 4));
        assertEquals("WAVE", new String(wav, 8, 4));
        assertEquals(16000, b.getInt(24));
        assertEquals(1, b.getShort(22));
        assertEquals(640, b.getInt(40));
    }
}
