package com.jagrosh.jmusicbot.voice;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** The text channels the bot may post in, by name. An empty list allows every channel. */
public final class PostChannels
{
    private PostChannels() {}

    public static boolean allowed(List<String> names, String channel)
    {
        if (names == null || names.isEmpty())
            return true;
        String c = channel == null ? "" : channel.toLowerCase(Locale.ROOT);
        return names.stream().anyMatch(n -> n.trim().equalsIgnoreCase(c));
    }

    /** What the bot says (as text) when asked to post somewhere it can't. */
    public static String refusal(List<String> names)
    {
        return "I can only post in " + names.stream().map(n -> "#" + n.trim()).collect(Collectors.joining(", ")) + ".";
    }
}
