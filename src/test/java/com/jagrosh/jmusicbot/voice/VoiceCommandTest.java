package com.jagrosh.jmusicbot.voice;

import com.jagrosh.jmusicbot.voice.VoiceCommand.Type;
import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceCommandTest
{
    @Test
    public void playWithWakeWordAndPunctuation()
    {
        VoiceCommand c = VoiceCommand.parse("Hey Gunna, play Fleetwood Mac.");
        assertEquals(Type.PLAY, c.type);
        assertEquals("fleetwood mac", c.arg);
    }

    @Test
    public void wakeWordMisspellings()
    {
        assertEquals(Type.SKIP, VoiceCommand.parse("Gunner skip!").type);
        assertEquals(Type.SKIP, VoiceCommand.parse("okay gonna, next song").type);
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
}
