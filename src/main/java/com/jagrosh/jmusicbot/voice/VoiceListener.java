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

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.User;

/**
 * Collects each speaker's audio into utterances. Discord stops sending
 * packets shortly after someone stops talking, and quiet frames don't count
 * as speech, so an utterance ends once a user has been silent for
 * {@link #END_SILENCE_MS}. {@link #flushIdle()} is polled to close them.
 */
public class VoiceListener implements AudioReceiveHandler
{
    static final long END_SILENCE_MS = 800;
    static final long MIN_SPEECH_MS = 400;
    static final long MAX_UTTERANCE_MS = 20_000;
    // below this RMS a frame is background noise, not speech
    static final double SPEECH_RMS = 500;

    private static class Utterance
    {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long lastSpeech;
        long speechMs;
    }

    private final Map<User, Utterance> active = new ConcurrentHashMap<>();
    private final BiConsumer<User, byte[]> onUtterance;

    /** @param onUtterance receives the speaker and a 16 kHz mono WAV */
    public VoiceListener(BiConsumer<User, byte[]> onUtterance)
    {
        this.onUtterance = onUtterance;
    }

    @Override
    public boolean canReceiveUser()
    {
        return true;
    }

    @Override
    public void handleUserAudio(UserAudio userAudio)
    {
        User user = userAudio.getUser();
        if (user == null || user.isBot())
            return;
        byte[] frame = VoiceAudio.toMono16k(userAudio.getAudioData(1.0));
        boolean speech = VoiceAudio.rms(frame) >= SPEECH_RMS;
        long now = System.currentTimeMillis();

        Utterance u = active.get(user);
        if (u == null)
        {
            if (!speech)
                return; // don't start an utterance on noise
            u = new Utterance();
            active.put(user, u);
        }
        synchronized (u)
        {
            u.pcm.writeBytes(frame);
            if (speech)
            {
                u.lastSpeech = now;
                u.speechMs += VoiceAudio.durationMs(frame.length);
            }
        }
        if (VoiceAudio.durationMs(u.pcm.size()) >= MAX_UTTERANCE_MS)
            finish(user);
    }

    /** Whether this user is mid-utterance right now. */
    public boolean isSpeaking(User user)
    {
        return active.containsKey(user);
    }

    /** Ends utterances whose speaker has gone quiet. Call every ~100 ms. */
    public void flushIdle()
    {
        long now = System.currentTimeMillis();
        for (Map.Entry<User, Utterance> e : active.entrySet())
            if (now - e.getValue().lastSpeech >= END_SILENCE_MS)
                finish(e.getKey());
    }

    private void finish(User user)
    {
        Utterance u = active.remove(user);
        if (u == null)
            return;
        byte[] pcm;
        long speechMs;
        synchronized (u)
        {
            pcm = u.pcm.toByteArray();
            speechMs = u.speechMs;
        }
        if (speechMs >= MIN_SPEECH_MS)
            onUtterance.accept(user, VoiceAudio.toWav(pcm));
    }
}
