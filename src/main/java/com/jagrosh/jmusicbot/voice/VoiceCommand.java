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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a transcript into a playback action, and works out whether the bot
 * was addressed: it only responds to speech containing one of its names
 * (Gunnabot, bot, robot, clanker) anywhere in the sentence. Speech-to-text
 * output is loose ("Gunner bot, skip.", "play the beatles, robot"), so names
 * and punctuation are stripped before matching. Anything unrecognised is CHAT,
 * which the LLM answers.
 */
public final class VoiceCommand
{
    // RADIO and MIX only come from the LLM; keyword parsing doesn't produce them
    public enum Type { PLAY, RADIO, MIX, SKIP, PAUSE, RESUME, STOP, NOW_PLAYING, CHAT }

    public final Type type;
    /** The song query for PLAY, the original transcript for CHAT, otherwise empty. */
    public final String arg;
    /** Whether the speaker used one of the bot's names. */
    public final boolean addressed;

    private VoiceCommand(Type type, String arg, boolean addressed)
    {
        this.type = type;
        this.arg = arg;
        this.addressed = addressed;
    }

    // the bot's names, and the ways STT tends to spell "gunnabot"
    private static final Pattern WAKE = Pattern.compile(
            "\\b(?:(?:hey|hi|ok|okay|oi)\\s+)?(?:(?:gunna|gunner|gonna|guna|gunnar)\\s?bot|robot|bot|clankers?)\\b");
    private static final Pattern PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}'\\s]");

    public static VoiceCommand parse(String transcript)
    {
        String original = transcript == null ? "" : transcript.trim();
        String t = PUNCTUATION.matcher(original.toLowerCase(Locale.ROOT)).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();
        Matcher wake = WAKE.matcher(t);
        boolean addressed = wake.find();
        if (addressed)
            t = wake.replaceAll(" ").replaceAll("\\s+", " ").trim();
        t = t.replaceFirst("^(?:please|can you|could you)\\s+", "").trim();

        t = t.replaceFirst("\\s+please$", "").trim();

        if (t.startsWith("play ") && t.length() > 5)
            return new VoiceCommand(Type.PLAY, t.substring(5).trim(), addressed);
        switch (t)
        {
            case "skip": case "skip it": case "skip this": case "skip this song": case "next": case "next song":
                return new VoiceCommand(Type.SKIP, "", addressed);
            case "pause": case "pause it": case "pause the music": case "pause music":
                return new VoiceCommand(Type.PAUSE, "", addressed);
            case "resume": case "unpause": case "continue": case "play": case "resume the music":
                return new VoiceCommand(Type.RESUME, "", addressed);
            case "stop": case "stop the music": case "stop music": case "stop playing":
                return new VoiceCommand(Type.STOP, "", addressed);
            case "what's playing": case "whats playing": case "what is playing": case "what song is this":
            case "what's this song": case "what is this song":
                return new VoiceCommand(Type.NOW_PLAYING, "", addressed);
            default:
                return new VoiceCommand(Type.CHAT, original, addressed);
        }
    }
}
