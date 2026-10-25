package com.jagrosh.jmusicbot.voice;

import com.jagrosh.jmusicbot.voice.VoiceCommand.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceFeaturesTest
{
    @Test
    public void spotsTheBotsOwnVoice()
    {
        List<String> said = List.of("The All Blacks won 43-21 in Sydney.", "I've posted the details in the chat.");
        // Parakeet hearing the bot through someone's speakers
        assertTrue(VoiceManager.isEcho("the All Blacks won 43 21 in Sydney", said));
        assertTrue(VoiceManager.isEcho("posted the details in the chat", said));
        // people talking about it, or asking for more
        assertFalse(VoiceManager.isEcho("wait who did the All Blacks play", said));
        assertFalse(VoiceManager.isEcho("play some Fleetwood Mac", said));
        assertFalse(VoiceManager.isEcho("anything", List.of()));
        // one word: only if the bot said exactly that
        assertTrue(VoiceManager.isEcho("Yes?", List.of("Yes?")));
        assertFalse(VoiceManager.isEcho("Sydney", said));
    }

    @Test
    public void remembersAndForgets() throws Exception
    {
        Path file = Files.createTempFile("memory", ".json");
        Files.delete(file);
        VoiceMemory memory = new VoiceMemory(file);
        memory.remember(1, "Regan", "Regan supports the Blues");
        memory.remember(1, "Regan", "regan supports the blues"); // a duplicate
        memory.remember(2, "Sam", "Sam hates Nickelback");
        assertEquals(List.of("Regan supports the Blues"), memory.notes(1));
        assertEquals("Regan: Regan supports the Blues. Sam: Sam hates Nickelback.", memory.describe(List.of(1L, 2L, 3L)));

        // survives a restart
        VoiceMemory reloaded = new VoiceMemory(file);
        assertEquals(List.of("Sam hates Nickelback"), reloaded.notes(2));
        assertTrue(reloaded.forget(2));
        assertFalse(reloaded.forget(2));
        assertEquals("", new VoiceMemory(file).describe(List.of(2L)));

        for (int i = 0; i < VoiceMemory.MAX_NOTES + 5; i++)
            reloaded.remember(1, "Regan", "fact " + i);
        assertEquals(VoiceMemory.MAX_NOTES, reloaded.notes(1).size());
        assertEquals("fact " + (VoiceMemory.MAX_NOTES + 4), reloaded.notes(1).get(VoiceMemory.MAX_NOTES - 1));
        Files.deleteIfExists(file);
    }

    @Test
    public void remindersFireAndSurviveARestart() throws Exception
    {
        Path file = Files.createTempFile("reminders", ".json");
        Files.delete(file);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        List<Reminders.Reminder> fired = new CopyOnWriteArrayList<>();
        Reminders reminders = new Reminders(file, timer, fired::add);
        reminders.add(10, 20, 30, "Regan", "start the game", 60);
        reminders.add(10, 20, 31, "Sam", "feed the cat", 120);
        assertEquals(1, reminders.cancel(10, 31));

        // a restart loads what's still pending
        List<Reminders.Reminder> afterRestart = new CopyOnWriteArrayList<>();
        new Reminders(file, timer, afterRestart::add);
        String saved = Files.readString(file);
        assertTrue(saved.contains("start the game"));
        assertFalse(saved.contains("feed the cat"));
        assertTrue(fired.isEmpty());
        timer.shutdownNow();
        Files.deleteIfExists(file);
    }

    @Test
    public void reactionsAreNotForTheBot()
    {
        // misrouted as stop, hush or chat on 2026-10-17
        for (String s : List.of("Holy shit.", "Oh my god.", "Yeah.", "Ooh.", "Um", "Yeah yeah yeah.", "Oh wow.", "Ha ha ha."))
            assertTrue(s, VoiceManager.isReaction(s));
        for (String s : List.of("Stop the music", "Oh thank god the interrupt's working now.", "yeah play some Six60", "SHUT UP"))
            assertFalse(s, VoiceManager.isReaction(s));
    }

    @Test
    public void stripsDeepSeekToolMarkup()
    {
        // from the 2026-10-17 logs, where it was read out loud
        String leaked = "<｜DSML｜toolcalls <｜DSML｜invoke name=\"postmessage\" <｜DSML｜parameter "
                + "name=\"message\" string=\"true\"gunna, here's what I found. It's the Bing Chilling meme. "
                + "Know Your Meme: https://knowyourmeme.com/memes/bing-chilling </｜DSML｜invoke </｜DSML｜toolcalls";
        assertTrue(Researcher.DSML.matcher(leaked).find());
        assertEquals("gunna, here's what I found. It's the Bing Chilling meme. Know Your Meme: "
                + "https://knowyourmeme.com/memes/bing-chilling", Researcher.stripToolMarkup(leaked));
        assertFalse(Researcher.DSML.matcher("A normal answer.").find());
    }

    @Test
    public void vagueMusicRequestsGoToTheBrain()
    {
        assertTrue(VoiceRouter.SYSTEM.contains("mood or loose description"));
        assertTrue(LlmClient.ACTIONS.contains("mood, feeling or loose description"));
    }

    @Test
    public void routesExplicitModeCommands()
    {
        assertEquals("explicit_on", VoiceRouter.parse("{\"to_bot\": true, \"command\": \"explicit_on\", \"args\": \"\"}").command);
        assertEquals("explicit_off", VoiceRouter.parse("{\"to_bot\": true, \"command\": \"explicit_off\", \"args\": \"\"}").command);
        // only in 18+ channels does the prompt describe them
        assertFalse(VoiceRouter.SYSTEM.contains("explicit_on"));
        assertTrue(VoiceRouter.MOOD.contains("explicit_on"));
    }

    @Test
    public void parsesRemindersAndMemory()
    {
        LlmClient.Decision d = LlmClient.parseDecision("{\"action\":\"remind\",\"query\":\"\",\"task\":\"start the game\","
                + "\"minutes\":20,\"remember\":\"\",\"post\":\"\",\"reply\":\"Will do.\"}");
        assertEquals(Type.REMIND, d.action);
        assertEquals("start the game", d.task);
        assertEquals(20, d.minutes, 0);
        // a reminder without a time isn't one
        assertEquals(Type.CHAT, LlmClient.parseDecision("{\"action\":\"remind\",\"task\":\"x\",\"minutes\":0,\"reply\":\"When?\"}").action);
        assertEquals(Type.FORGET, LlmClient.parseDecision("{\"action\":\"forget\",\"reply\":\"Done.\"}").action);
        LlmClient.Decision m = LlmClient.parseDecision("{\"action\":\"chat\",\"remember\":\"Regan supports the Blues\",\"reply\":\"Nice.\"}");
        assertEquals("Regan supports the Blues", m.remember);
    }
}
