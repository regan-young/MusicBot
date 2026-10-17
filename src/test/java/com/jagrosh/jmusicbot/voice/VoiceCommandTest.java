package com.jagrosh.jmusicbot.voice;

import com.jagrosh.jmusicbot.voice.VoiceCommand.Type;
import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceCommandTest
{
    @Test
    public void playWithWakeWordAndPunctuation()
    {
        VoiceCommand c = VoiceCommand.parse("Hey Gunnabot, play Fleetwood Mac.");
        assertEquals(Type.PLAY, c.type);
        assertEquals("fleetwood mac", c.arg);
    }

    @Test
    public void wakeWordMisspellings()
    {
        assertEquals(Type.SKIP, VoiceCommand.parse("Gunner bot skip!").type);
        assertEquals(Type.SKIP, VoiceCommand.parse("next song, robot").type);
    }

    @Test
    public void simpleControls()
    {
        assertEquals(Type.PAUSE, VoiceCommand.parse("Pause.").type);
        assertEquals(Type.RESUME, VoiceCommand.parse("resume").type);
        assertEquals(Type.STOP, VoiceCommand.parse("Stop the music").type);
        assertEquals(Type.NOW_PLAYING, VoiceCommand.parse("What's playing?").type);
        assertEquals(Type.SKIP, VoiceCommand.parse("can you skip this song").type);
    }

    @Test
    public void barePlayResumes()
    {
        assertEquals(Type.RESUME, VoiceCommand.parse("Play.").type);
    }

    @Test
    public void anythingElseIsChatWithOriginalText()
    {
        VoiceCommand c = VoiceCommand.parse("  How's it going, mate?  ");
        assertEquals(Type.CHAT, c.type);
        assertEquals("How's it going, mate?", c.arg);
        // a command word mid-sentence is not a command
        assertEquals(Type.CHAT, VoiceCommand.parse("I don't want to stop yet").type);
    }

    @Test
    public void addressedByAnyNameAnywhere()
    {
        assertTrue(VoiceCommand.parse("Gunnabot, what's the weather like?").addressed);
        assertTrue(VoiceCommand.parse("gonna bot skip").addressed);
        assertTrue(VoiceCommand.parse("What do you think, robot?").addressed);
        assertTrue(VoiceCommand.parse("Hey clanker, play some jazz").addressed);
        assertTrue(VoiceCommand.parse("Bot. Pause.").addressed);
        // real transcriptions of "Gunnabot" from the logs
        assertTrue(VoiceCommand.parse("Annabot, I think you have trouble with the New Zealand accent.").addressed);
        assertTrue(VoiceCommand.parse("A guttabot, tell us a joke.").addressed);
        assertTrue(VoiceCommand.parse("Gunnerbot, read me a summary").addressed);
        assertEquals(Type.PLAY, VoiceCommand.parse("Hey clanker, play some jazz").type);
        assertEquals("some jazz", VoiceCommand.parse("Hey clanker, play some jazz").arg);
        assertEquals(Type.PAUSE, VoiceCommand.parse("Bot. Pause.").type);
    }

    @Test
    public void notAddressedWithoutAName()
    {
        assertFalse(VoiceCommand.parse("what's the weather like?").addressed);
        assertFalse(VoiceCommand.parse("I'm gonna skip lunch").addressed);
        assertFalse(VoiceCommand.parse("grab me a bottle").addressed);
        assertFalse(VoiceCommand.parse("we went to the robotics club").addressed);
        // the LLM gets the transcript as spoken
        assertEquals("Gunnabot, what's up?", VoiceCommand.parse("Gunnabot, what's up?").arg);
    }

    @Test
    public void forSpeechStripsMarkdownAndEmoji()
    {
        assertEquals("Sure, here's the plan: rest up.", LlmClient.forSpeech("**Sure**, here's the _plan_: rest up. 😀"));
        assertEquals("See the docs for more.", LlmClient.forSpeech("See [the docs](https://example.com) for more."));
    }
}
