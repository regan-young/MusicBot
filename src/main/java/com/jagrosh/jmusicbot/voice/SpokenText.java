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
import java.util.Random;
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

    /**
     * What to say to someone joining the voice channel. A display name can hold
     * emoji, markdown, links or "[tags]" the voice would read out, so only
     * letters, digits and a few joining marks are kept.
     */
    static String greeting(String displayName)
    {
        String name = cleanName(displayName);
        return name.isEmpty() ? "Oh hey there" : "Oh hey, " + name;
    }

    /** A display name with nothing the voice would read out, cut short; empty if nothing is left. */
    private static String cleanName(String displayName)
    {
        String name = LlmClient.forSpeech(displayName == null ? "" : displayName)
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("https?://\\S+", "")
                .replaceAll("[^\\p{L}\\p{N} '.-]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (name.length() > 32)
            name = name.substring(0, 32).trim();
        return name;
    }

    // {name} is the person joining. Several sentences each, since they're spoken one by one.
    private static final String[] WELCOMES = {
        "Ladies and gentlemen, hush your chatter and lift your eyes. {name} has entered the building! "
            + "Somewhere a choir of angels just cleared its throat. The room is warmer, the music is better, "
            + "and the snacks taste fresher, all because you showed up. Welcome, {name}. We are honoured.",
        "Attention, everyone! A very important person has just joined us. Roll out the red carpet, polish the "
            + "chandeliers and cue the trumpets, because {name} is here! We have waited all day for this moment. "
            + "Take a seat, take a bow, and make yourself completely at home.",
        "Welcome, welcome, a thousand welcomes, {name}! Let it be known across the land that you have arrived. "
            + "The bards will write songs about this moment. The dragons are putting down their swords. "
            + "Pull up a chair, friend, because the party just got a whole lot better.",
        "Breaking news from the voice channel! Sources confirm that {name} has just walked in, and the crowd is "
            + "going wild. Experts say this is the best thing to happen all week. "
            + "So please, {name}, enjoy the tunes, enjoy the company, and know that you are very, very welcome here.",
        "Drumroll please! Dum dum dum dum dum! Presenting the one, the only, the magnificent {name}! "
            + "You light up the room like a disco ball at midnight. We promise to laugh at your jokes, "
            + "play your favourite songs and save you the comfiest seat. Welcome in, {name}!",
        "Greetings, {name}, and welcome to the finest voice channel in all of Discord. We have music, we have "
            + "good vibes, and now we have you, which makes everything just perfect. "
            + "Stretch your legs, grab a drink, and let the good times roll!"
    };

    /**
     * What to say to someone joining the voice channel with ?greet on: their
     * own greeting if they've set one, otherwise one of several long, over the
     * top welcome speeches picked at random.
     */
    static String welcome(String displayName, String custom, Random random)
    {
        if (custom != null && !custom.isBlank())
            return greeting(displayName, custom);
        String name = cleanName(displayName);
        return WELCOMES[random.nextInt(WELCOMES.length)].replace("{name}", name.isEmpty() ? "my friend" : name);
    }

    /**
     * Someone's own greeting, with {name} as their display name, cleaned the
     * same way as a name (no emoji, markdown, links or [tags] for the voice to
     * read out) but keeping sentence punctuation. Falls back to the usual
     * greeting when they haven't set one or nothing speakable is left.
     */
    static String greeting(String displayName, String custom)
    {
        String usual = greeting(displayName);
        if (custom == null || custom.isBlank())
            return usual;
        String name = usual.startsWith("Oh hey, ") ? usual.substring("Oh hey, ".length()) : "you";
        String text = LlmClient.forSpeech(custom)
                .replaceAll("\\[[^\\]]*\\]", "")
                .replaceAll("https?://\\S+", "")
                .replaceAll("[^\\p{L}\\p{N}{} '.,!?:;&-]", "")
                .replace("{name}", "\u0000")
                .replaceAll("[{}]", "")
                .replace("\u0000", name)
                .replaceAll("\\s+", " ")
                .trim();
        if (text.length() > Greetings.MAX_LENGTH + 32)
            text = text.substring(0, Greetings.MAX_LENGTH + 32).trim();
        return text.replaceAll("[\\p{P}\\s]", "").isEmpty() ? usual : text;
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

    private static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("\\[([A-Za-z][A-Za-z ]{1,20})\\]\\s*");

    /**
     * Keeps the expressive tags the voice understands ("[laugh]", "[sigh]")
     * and drops any other, which a voice would read out as words.
     * @param allowed the tags the voice knows, without brackets; none means the voice has no tags
     */
    static String onlyTags(String text, java.util.Collection<String> allowed)
    {
        java.util.regex.Matcher m = TAG.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find())
            m.appendReplacement(sb, allowed.contains(m.group(1).toLowerCase(Locale.ROOT))
                    ? java.util.regex.Matcher.quoteReplacement("[" + m.group(1).toLowerCase(Locale.ROOT) + "] ") : "");
        m.appendTail(sb);
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** The songs in a play query: one, or several separated by " | " when a run of songs was asked for. At most five. */
    static List<String> songs(String query)
    {
        List<String> out = new ArrayList<>();
        for (String song : query.split("\\s*\\|\\s*"))
            if (!song.isBlank() && out.size() < 5)
                out.add(song.trim());
        return out;
    }
}
