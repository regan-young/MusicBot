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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What can be told from the words alone, about a line someone said or one the
 * bot is about to say: whether it's just a reaction, whether it's the bot's
 * own voice coming back, and how to cut a reply up for speaking.
 */
final class SpokenText
{
    private SpokenText() {}

    private static final Set<String> REACTION_WORDS = Set.of("oh", "my", "god", "gosh", "holy", "shit", "fuck", "fucking",
            "damn", "wow", "whoa", "yeah", "yes", "yep", "yup", "no", "nah", "nope", "ooh", "oo", "ah", "aw", "um", "uh",
            "umm", "uhh", "hmm", "mm", "ha", "haha", "hahaha", "lol", "okay", "ok", "right", "sure", "nice", "jesus",
            "christ", "what", "huh", "bro", "dude", "man", "thank", "thanks", "cool", "geez", "jeez", "lord", "the", "hell");

    /**
     * A short reaction or exclamation ("Holy shit.", "Yeah.", "Ooh.", "Um"):
     * not for the bot unless it's answering what the bot just asked them.
     * The router got these wrong (as stop, hush or chat) after the bot spoke.
     */
    static boolean isReaction(String text)
    {
        List<String> words = words(text);
        return !words.isEmpty() && words.size() <= 4 && REACTION_WORDS.containsAll(words);
    }

    /**
     * Whether a transcript is mostly the bot's own recent words, which happens
     * when someone without headphones has the bot coming out of their speakers.
     */
    static boolean isEcho(String heard, List<String> botSaid)
    {
        List<String> words = words(heard);
        if (words.isEmpty() || botSaid.isEmpty())
            return false;
        if (words.size() == 1)
            return botSaid.stream().anyMatch(b -> words(b).equals(words));
        Set<String> said = new HashSet<>();
        botSaid.forEach(b -> said.addAll(words(b)));
        long matching = words.stream().filter(said::contains).count();
        return matching >= Math.ceil(words.size() * 0.75);
    }

    private static List<String> words(String text)
    {
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}' ]", " ").split("\\s+"))
            if (!w.isBlank())
                out.add(w);
        return out;
    }

    // a sentence shorter than this joins the next, so clips aren't choppy
    private static final int MIN_SENTENCE = 24;

    /** Splits a reply into sentences for speaking one by one. */
    static List<String> sentences(String text)
    {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        // don't split after titles like "Mr." in "Mr. Brightside"
        for (String s : text.trim().split("(?<!\\b(?:Mr|Mrs|Ms|Dr|St|vs))(?<=[.!?])\\s+"))
        {
            if (cur.length() > 0)
                cur.append(' ');
            cur.append(s);
            if (cur.length() >= MIN_SENTENCE)
            {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0)
            out.add(cur.toString());
        return out;
    }

    /** Turns a command's reply like "\u274C No results found for `xyz`!" into something speakable. */
    static String spokenReply(String reply)
    {
        String s = LlmClient.forSpeech(reply.replaceAll("<[@#&!:a-zA-Z0-9_]+>", ""))
                .replaceAll("[\\x{2190}-\\x{2BFF}]", "")
                .trim();
        int cut = s.indexOf(". ");
        if (cut > 0 && cut < 160)
            s = s.substring(0, cut + 1);
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }
}
