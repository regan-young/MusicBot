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
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects each speaker's audio into utterances. Discord stops sending
 * packets shortly after someone stops talking, and quiet frames don't count
 * as speech, so silence is measured from the last loud frame.
 * {@link #flushIdle()} is polled to close utterances.
 *
 * With a {@link SmartTurn} model, a short pause is enough when the model
 * hears that they've finished (falling intonation, a complete sentence),
 * and a longer one is allowed when they haven't ("can you, um..."). It's
 * asked after {@link #CHECK_SILENCE_MS} and again after
 * {@link #RECHECK_SILENCE_MS}; after {@link #MAX_SILENCE_MS} the utterance
 * ends anyway. Without the model it ends after {@link #END_SILENCE_MS}.
 */
public class VoiceListener implements AudioReceiveHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceListener.class);

    static final long END_SILENCE_MS = 800;
    static final long CHECK_SILENCE_MS = 200;
    static final long RECHECK_SILENCE_MS = 600;
    static final long MAX_SILENCE_MS = 1500;
    static final float COMPLETE = 0.5f;
    static final long MIN_SPEECH_MS = 400;
    // this much speech while the bot is answering them is someone talking over it
    static final long TALKING_MS = 600;
    static final long MAX_UTTERANCE_MS = 20_000;
    // below this RMS a frame is background noise, not speech
    static final double SPEECH_RMS = 500;

    /** Receives each finished utterance. */
    public interface Handler
    {
        /**
         * @param wav 16 kHz mono WAV
         * @param spokeUntil when the speaker went quiet (epoch ms)
         * @param ending how the end was decided, for the logs
         */
        void onUtterance(User user, byte[] wav, long spokeUntil, String ending);
    }

    private static class Utterance
    {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long lastSpeech;
        int speechEnd; // bytes of pcm up to the last loud frame
        long speechMs;
        // the smart-turn checks done for the current pause (0, 1 or 2), and
        // whether one is running
        int checks;
        boolean checking;
        boolean talking; // onTalking has been called for this utterance
    }

    private final Map<User, Utterance> active = new ConcurrentHashMap<>();
    private final Handler handler;
    private final Consumer<User> onTalking;
    private final SmartTurn smartTurn;
    private final ExecutorService checker;

    /**
     * @param smartTurn the end-of-turn model, or null to use a fixed silence
     * @param checker runs the model, off the polling thread
     * @param onTalking called once an utterance has TALKING_MS of speech, before it ends
     */
    public VoiceListener(Handler handler, Consumer<User> onTalking, SmartTurn smartTurn, ExecutorService checker)
    {
        this.handler = handler;
        this.onTalking = onTalking;
        this.smartTurn = smartTurn;
        this.checker = checker;
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
                u.speechEnd = u.pcm.size();
                u.speechMs += VoiceAudio.durationMs(frame.length);
                u.checks = 0; // a new pause will need new checks
            }
            if (u.speechMs >= TALKING_MS && !u.talking)
            {
                u.talking = true;
                onTalking.accept(user);
            }
        }
        if (VoiceAudio.durationMs(u.pcm.size()) >= MAX_UTTERANCE_MS)
            finish(user, u, "max length");
    }

    /** Whether anyone is mid-utterance right now. */
    public boolean isAnyoneSpeaking()
    {
        return !active.isEmpty();
    }

    /** Whether this user is mid-utterance right now. */
    public boolean isSpeaking(User user)
    {
        return active.containsKey(user);
    }

    /** Ends utterances whose speaker has finished. Call every ~100 ms. */
    public void flushIdle()
    {
        long now = System.currentTimeMillis();
        for (Map.Entry<User, Utterance> e : active.entrySet())
        {
            User user = e.getKey();
            Utterance u = e.getValue();
            byte[] pcm;
            long silence, mark;
            synchronized (u)
            {
                silence = now - u.lastSpeech;
                if (smartTurn == null)
                {
                    if (silence >= END_SILENCE_MS)
                        finish(user, u, "silence " + silence + " ms");
                    continue;
                }
                if (silence >= MAX_SILENCE_MS)
                {
                    finish(user, u, "silence " + silence + " ms");
                    continue;
                }
                long due = u.checks == 0 ? CHECK_SILENCE_MS : RECHECK_SILENCE_MS;
                if (u.checking || u.checks >= 2 || silence < due || u.speechMs < MIN_SPEECH_MS)
                    continue;
                u.checking = true;
                mark = u.lastSpeech;
                pcm = withSilence(u.pcm.toByteArray(), u.speechEnd, silence);
            }
            checker.submit(() -> check(user, u, pcm, mark, silence));
        }
    }

    private void check(User user, Utterance u, byte[] pcm, long mark, long silence)
    {
        float p;
        try
        {
            p = smartTurn.probability(pcm);
        }
        catch (Exception e)
        {
            LOG.warn("Smart Turn failed; waiting for silence instead", e);
            p = 0;
        }
        synchronized (u)
        {
            u.checking = false;
            if (u.lastSpeech != mark)
                return; // they started talking again; the next pause gets fresh checks
            u.checks++;
            if (p >= COMPLETE)
                finish(user, u, String.format("smart turn %.2f after %d ms", p, silence));
        }
    }

    /**
     * The model expects the pause itself at the end of the audio, but Discord
     * sends only a few quiet frames once someone stops talking. Keeps those
     * and pads with zeros to the length of the pause so far.
     */
    static byte[] withSilence(byte[] pcm, int speechEnd, long silenceMs)
    {
        int length = Math.max(pcm.length, speechEnd + (int) (silenceMs * SmartTurn.RATE / 1000) * 2);
        return Arrays.copyOf(pcm, length);
    }

    private void finish(User user, Utterance u, String ending)
    {
        // remove(key, value): only the utterance this decision was about
        if (!active.remove(user, u))
            return;
        byte[] pcm;
        long speechMs, spokeUntil;
        synchronized (u)
        {
            pcm = u.pcm.toByteArray();
            speechMs = u.speechMs;
            spokeUntil = u.lastSpeech;
        }
        if (speechMs >= MIN_SPEECH_MS)
            handler.onUtterance(user, VoiceAudio.toWav(pcm), spokeUntil, ending);
    }
}
