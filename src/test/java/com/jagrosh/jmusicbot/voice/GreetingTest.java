package com.jagrosh.jmusicbot.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
    public void welcomeSpeechesAreLongAndVary()
    {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 6; i++)
        {
            String w = SpokenText.welcome("Sam 😎", "", new java.util.Random(i));
            assertTrue(w.contains("Sam"));
            assertFalse(w.contains("{"));
            assertTrue(w.length() > 150);
            seen.add(w);
        }
        assertTrue(seen.size() > 1);
        assertTrue(SpokenText.welcome("😎", null, new java.util.Random(1)).contains("my friend"));
    }

    @Test
    public void stockSpeechesSaidLatelyWaitTheirTurn()
    {
        // five welcomes, each to someone different, never repeating a speech while unused ones remain
        java.util.List<String> recent = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++)
        {
            String w = SpokenText.welcome("Person" + i, "", new java.util.Random(i * 31L), recent);
            assertFalse(recent.stream().anyMatch(r -> r.substring(0, 30).equals(w.substring(0, 30))));
            recent.add(w);
        }
    }

    @Test
    public void writtenWelcomesAreMadeSafeToSpeak()
    {
        assertEquals("Ladies and gentlemen, Sam is here! Welcome!",
                SpokenText.parseWelcome("{\"welcome\": \"**Ladies and gentlemen**, Sam is here! https://x.y Welcome!\"}"));
        assertNull(SpokenText.parseWelcome("not json"));
        assertNull(SpokenText.parseWelcome("{\"welcome\": \"\"}"));
        assertNull(SpokenText.parseWelcome(null));
        String longOne = "This is a sentence about Sam. ".repeat(40);
        String cut = SpokenText.parseWelcome("{\"welcome\": \"" + longOne + "\"}");
        assertTrue(cut.length() <= SpokenText.WELCOME_MAX && cut.endsWith("."));
    }

    @Test
    public void ownGreetingOverridesTheSpeech()
    {
        assertEquals("The legend Sam has arrived!", SpokenText.welcome("Sam", "The legend {name} has arrived!", new java.util.Random(1)));
    }

    @Test
    public void longNamesAreCut()
    {
        assertEquals("Oh hey, " + "a".repeat(32), SpokenText.greeting("a".repeat(80)));
    }
}
