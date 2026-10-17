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
import java.util.regex.Pattern;

/**
 * Turns a transcript into a playback action. Speech-to-text output is loose
 * ("Gunna, skip.", "hey gunner play the beatles"), so this strips an optional
 * wake word and punctuation before matching. Anything unrecognised is CHAT,
 * which the bot answers by repeating it back.
 */
public final class VoiceCommand
{
    public enum Type { PLAY, SKIP, PAUSE, RESUME, STOP, NOW_PLAYING, CHAT }

    public final Type type;
    /** The song query for PLAY, the original transcript for CHAT, otherwise empty. */
    public final String arg;

    private VoiceCommand(Type type, String arg)
    {
        this.type = type;
        this.arg = arg;
    }

    // "gunna", and the ways STT tends to spell it
    private static final Pattern WAKE = Pattern.compile(
            "^(?:(?:hey|hi|ok|okay|yo)\\s+)?(?:gunna|gunner|gonna|guna|gunnar)\\b\\s*");
    private static final Pattern PUNCTUATION = Pattern.compile("[^\\p{L}\\p{N}'\\s]");

    public static VoiceCommand parse(String transcript)
    {
        String original = transcript == null ? "" : transcript.trim();
        String t = PUNCTUATION.matcher(original.toLowerCase(Locale.ROOT)).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();
        t = WAKE.matcher(t).replaceFirst("").trim();
        t = t.replaceFirst("^(?:please|can you|could you)\\s+", "").trim();

        if (t.startsWith("play ") && t.length() > 5)
            return new VoiceCommand(Type.PLAY, t.substring(5).trim());
        switch (t)
        {
            case "skip": case "skip it": case "skip this": case "skip this song": case "next": case "next song":
                return new VoiceCommand(Type.SKIP, "");
            case "pause": case "pause it": case "pause the music": case "pause music":
                return new VoiceCommand(Type.PAUSE, "");
            case "resume": case "unpause": case "continue": case "play": case "resume the music":
                return new VoiceCommand(Type.RESUME, "");
            case "stop": case "stop the music": case "stop music": case "stop playing":
                return new VoiceCommand(Type.STOP, "");
            case "what's playing": case "whats playing": case "what is playing": case "what song is this":
            case "what's this song": case "what is this song":
                return new VoiceCommand(Type.NOW_PLAYING, "");
            default:
                return new VoiceCommand(Type.CHAT, original);
        }
    }
}
