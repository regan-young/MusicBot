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

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory;
import java.util.List;

/**
 * Vocal removal. Singers are mixed dead centre, so they are the same in the
 * left and right channels; the difference of the two cancels them out. The
 * result is mono (sent to both channels), which is the usual price of this
 * trick. It works on the decoded audio, so every source behaves the same.
 */
public class KaraokeFilter implements FloatPcmAudioFilter
{
    /** Builds the filter for each track; lavaplayer calls this as tracks start. */
    public static final PcmFilterFactory FACTORY = (track, format, output) ->
            format.channelCount == 2 ? List.<AudioFilter>of(new KaraokeFilter(output)) : List.<AudioFilter>of();

    private final FloatPcmAudioFilter next;

    KaraokeFilter(FloatPcmAudioFilter next)
    {
        this.next = next;
    }

    @Override
    public void process(float[][] input, int offset, int length) throws InterruptedException
    {
        removeCentre(input[0], input[1], offset, length);
        next.process(input, offset, length);
    }

    static void removeCentre(float[] left, float[] right, int offset, int length)
    {
        for(int i = offset; i < offset + length; i++)
        {
            float side = (left[i] - right[i]) / 2;
            left[i] = side;
            right[i] = side;
        }
    }

    @Override
    public void seekPerformed(long requestedTime, long providedTime) {}

    @Override
    public void flush() {}

    @Override
    public void close() {}
}
