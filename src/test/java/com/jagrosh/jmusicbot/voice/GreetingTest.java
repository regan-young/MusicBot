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
    public void ownGreetingsWithTheirName()
    {
        assertEquals("The legend Sam has arrived!", SpokenText.greeting("Sam 😎", "The legend {name} has arrived! [laugh] 🎉"));
        assertEquals("Welcome back to the stage, darling.", SpokenText.greeting("Sam", "**Welcome back** to the stage, darling."));
        // nothing speakable, or not set: the usual
        assertEquals("Oh hey, Sam", SpokenText.greeting("Sam", "🎉 https://x.example"));
        assertEquals("Oh hey, Sam", SpokenText.greeting("Sam", ""));
        assertEquals("Hi you", SpokenText.greeting("😎", "Hi {name}"));
    }

    @Test
    public void greetingsAreKeptPerPerson() throws Exception
    {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("greetings", ".json");
        java.nio.file.Files.delete(file);
        Greetings g = new Greetings(file);
        assertEquals("Here comes {name}", g.set(1, "Sam", "  Here   comes {name} "));
        assertEquals("x".repeat(Greetings.MAX_LENGTH), g.set(2, "Devon", "x".repeat(400)));
        assertEquals("Here comes {name}", new Greetings(file).get(1));
        assertEquals("", g.set(1, "Sam", "")); // back to the usual
        assertEquals("", new Greetings(file).get(1));
        java.nio.file.Files.deleteIfExists(file);
    }

    @Test
    public void longNamesAreCut()
    {
        assertEquals("Oh hey, " + "a".repeat(32), SpokenText.greeting("a".repeat(80)));
    }
}
