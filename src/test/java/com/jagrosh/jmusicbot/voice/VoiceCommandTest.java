package com.jagrosh.jmusicbot.voice;

import com.jagrosh.jmusicbot.voice.VoiceCommand.Type;
import java.util.List;
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
    public void skipToASong()
    {
        VoiceCommand c = VoiceCommand.parse("Gunnabot, skip to Fleetwood Mac.");
        assertEquals(Type.SKIP_TO, c.type);
        assertEquals("fleetwood mac", c.arg);
        assertEquals("number 3", VoiceCommand.parse("jump to number 3").arg);
        // "the next one" is a plain skip, not a song called that
        assertEquals(Type.SKIP, VoiceCommand.parse("skip to the next song").type);
        assertEquals(Type.SKIP, VoiceCommand.parse("skip to next").type);
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
    public void nameOnly()
    {
        // from the 2026-10-17 logs: the question came as the next utterance
        assertTrue(VoiceCommand.isNameOnly("Gunner bot. Um"));
        assertTrue(VoiceCommand.isNameOnly("Hey Gunnabot?"));
        assertTrue(VoiceCommand.isNameOnly("Robot."));
        assertFalse(VoiceCommand.isNameOnly("Hey gunnerbot. Um tell me a joke."));
        assertFalse(VoiceCommand.isNameOnly("Um, so yeah."));
        assertFalse(VoiceCommand.isNameOnly("Bot, skip"));
    }

    @Test
    public void sentencesForSpeech()
    {
        assertEquals(List.of("Why do musicians hate elevators?", "Because they can't stand the ups and downs."),
                SpokenText.sentences("Why do musicians hate elevators? Because they can't stand the ups and downs."));
        // short sentences join the next one, and titles don't end a sentence
        assertEquals(List.of("Sure. Queuing Mr. Brightside now."), SpokenText.sentences("Sure. Queuing Mr. Brightside now."));
        assertEquals(List.of("Yes?"), SpokenText.sentences("Yes?"));
    }

    @Test
    public void forSpeechStripsMarkdownAndEmoji()
    {
        assertEquals("Sure, here's the plan: rest up.", LlmClient.forSpeech("**Sure**, here's the _plan_: rest up. 😀"));
        assertEquals("See the docs for more.", LlmClient.forSpeech("See [the docs](https://example.com) for more."));
    }

    @Test
    public void namesAsSpeechRecognitionHasWrittenThem()
    {
        // from the 12 November session
        for (String heard : new String[] { "Stop. Stop, Calabod, stop.", "Hey, Ganabod did you play the song?",
                "Wait a minute, Gunnerpot's recording.", "Gunbot, can you turn it down by 20?", "Cannabot, can you please skip this song?" })
            assertTrue(heard, VoiceCommand.parse(heard).addressed);
        // and ordinary words that end the same way aren't it
        for (String heard : new String[] { "I had a jackpot last night", "Nobody saw the body", "He bought a teapot" })
            assertFalse(heard, VoiceCommand.parse(heard).addressed);
    }
}
