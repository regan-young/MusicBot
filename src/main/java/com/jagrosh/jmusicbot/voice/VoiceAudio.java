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
package com.jagrosh.jmusicbot.voice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * PCM helpers for voice receive. JDA hands us 20 ms frames of 48 kHz 16-bit
 * stereo big-endian PCM; speech-to-text only needs 16 kHz mono, which is a
 * sixth of the size to buffer and upload.
 */
public final class VoiceAudio
{
    public static final int SAMPLE_RATE = 16000;

    private VoiceAudio() {}

    /**
     * Downmixes 48 kHz stereo big-endian PCM to 16 kHz mono little-endian PCM.
     * Each output sample averages three stereo input frames, which doubles as
     * a crude low-pass filter for the 3:1 decimation.
     */
    public static byte[] toMono16k(byte[] stereo48kBigEndian)
    {
        ByteBuffer in = ByteBuffer.wrap(stereo48kBigEndian).order(ByteOrder.BIG_ENDIAN);
        int frames = stereo48kBigEndian.length / 4;
        int outSamples = frames / 3;
        ByteBuffer out = ByteBuffer.allocate(outSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < outSamples; i++)
        {
            int sum = 0;
            for (int j = 0; j < 3; j++)
                sum += in.getShort() + in.getShort();
            out.putShort((short) (sum / 6));
        }
        return out.array();
    }

    /** Root-mean-square level of 16-bit little-endian mono PCM, 0..32768. */
    public static double rms(byte[] mono16LittleEndian)
    {
        ByteBuffer in = ByteBuffer.wrap(mono16LittleEndian).order(ByteOrder.LITTLE_ENDIAN);
        int samples = mono16LittleEndian.length / 2;
        if (samples == 0)
            return 0;
        double sum = 0;
        for (int i = 0; i < samples; i++)
        {
            double s = in.getShort();
            sum += s * s;
        }
        return Math.sqrt(sum / samples);
    }

    /** Wraps 16 kHz mono little-endian PCM in a WAV header. */
    public static byte[] toWav(byte[] mono16kLittleEndian)
    {
        int dataLen = mono16kLittleEndian.length;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] { 'R', 'I', 'F', 'F' }).putInt(36 + dataLen)
         .put(new byte[] { 'W', 'A', 'V', 'E', 'f', 'm', 't', ' ' })
         .putInt(16).putShort((short) 1).putShort((short) 1)
         .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
         .putShort((short) 2).putShort((short) 16)
         .put(new byte[] { 'd', 'a', 't', 'a' }).putInt(dataLen)
         .put(mono16kLittleEndian);
        return b.array();
    }

    /** Duration in milliseconds of 16 kHz mono 16-bit PCM. */
    public static long durationMs(int pcmBytes)
    {
        return pcmBytes * 1000L / (SAMPLE_RATE * 2);
    }
}
