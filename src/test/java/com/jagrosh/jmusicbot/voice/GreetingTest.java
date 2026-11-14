package com.jagrosh.jmusicbot.voice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GreetingTest
{
    @Test
    public void greetsByName()
    {
        assertEquals("Oh hey, Sam", SpokenText.greeting("Sam"));
    }

    @Test
    public void dropsEmojiMarkdownTagsAndLinks()
    {
        assertEquals("Oh hey, Sam", SpokenText.greeting("**Sam** 😎 [laugh] https://x.example/a"));
    }

    @Test
    public void fallsBackWhenNothingSpeakable()
    {
        assertEquals("Oh hey there", SpokenText.greeting("😎"));
        assertEquals("Oh hey there", SpokenText.greeting(null));
    }

    @Test
    public void longNamesAreCut()
    {
        assertEquals("Oh hey, " + "a".repeat(32), SpokenText.greeting("a".repeat(80)));
    }
}
