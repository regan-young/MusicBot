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
        assertTrue(SpokenText.isEcho("the All Blacks won 43 21 in Sydney", said));
        assertTrue(SpokenText.isEcho("posted the details in the chat", said));
        // people talking about it, or asking for more
        assertFalse(SpokenText.isEcho("wait who did the All Blacks play", said));
        assertFalse(SpokenText.isEcho("play some Fleetwood Mac", said));
        assertFalse(SpokenText.isEcho("anything", List.of()));
        // one word: only if the bot said exactly that
        assertTrue(SpokenText.isEcho("Yes?", List.of("Yes?")));
        assertFalse(SpokenText.isEcho("Sydney", said));
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
            assertTrue(s, SpokenText.isReaction(s));
        for (String s : List.of("Stop the music", "Oh thank god the interrupt's working now.", "yeah play some Six60", "SHUT UP"))
            assertFalse(s, SpokenText.isReaction(s));
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
    public void routesCrudeModeCommands()
    {
        assertEquals("crude_on", VoiceRouter.parse("{\"to_bot\": true, \"command\": \"crude_on\", \"args\": \"\"}").command);
        assertEquals("crude_off", VoiceRouter.parse("{\"to_bot\": true, \"command\": \"crude_off\", \"args\": \"\"}").command);
        // the prompt only describes the modes that are available
        assertEquals(VoiceRouter.SYSTEM, VoiceRouter.system(null, null));
        assertTrue(VoiceRouter.system(null, false).contains("crude_on"));
        assertFalse(VoiceRouter.system(null, true).contains("explicit_on"));
        assertTrue(VoiceRouter.system(false, false).contains("explicit_on"));
    }

    @Test
    public void spotsARepeatedCommand()
    {
        // real lines the router answered with crude_on
        assertTrue(VoiceRouter.isRepeat("crude_on", "", "Back second.", null, false, 30));
        assertTrue(VoiceRouter.isRepeat("crude_on", "", "How did you say that?", null, false, 30));
        assertTrue(VoiceRouter.isRepeat("crude_on", "", "Yell at Gunner, abuse gunna, roast gunner.", null, false, 30));
        // already on, even when it's asked for again
        assertTrue(VoiceRouter.isRepeat("crude_on", "", "crude mode on", null, true, 30));
        assertFalse(VoiceRouter.isRepeat("crude_on", "", "Um hey gunablot, crude mode on.", null, false, 30));
        assertFalse(VoiceRouter.isRepeat("crude_on", "", "bot, start swearing at us", null, false, 30));
        assertFalse(VoiceRouter.isRepeat("crude_on", "", "stop holding back", null, false, 30));

        assertTrue(VoiceRouter.isRepeat("crude_off", "", "tone it down", null, false, 30));
        assertFalse(VoiceRouter.isRepeat("crude_off", "", "tone it down", null, true, 30));
        assertTrue(VoiceRouter.isRepeat("explicit_on", "", "go NSFW", true, null, 30));
        assertFalse(VoiceRouter.isRepeat("explicit_on", "", "go NSFW", false, null, 30));
        assertTrue(VoiceRouter.isRepeat("explicit_off", "", "back to normal", false, null, 30));

        // "Thousand" came back as the volume just set
        assertTrue(VoiceRouter.isRepeat("volume", "30", "Thousand.", null, null, 30));
        assertFalse(VoiceRouter.isRepeat("volume", "50", "make it fifty", null, null, 30));
        assertFalse(VoiceRouter.isRepeat("skip", "", "skip", null, null, 30));
    }

    private static boolean musicRepeat(String command, String args, String said)
    {
        return VoiceRouter.isRepeat(command, args, said, null, null, 30);
    }

    @Test
    public void musicCommandsNeedALineAboutThem()
    {
        // real lines the router answered with the song just queued, or made one up for
        String moonlight = "don't blame it on the moonlight";
        assertTrue(musicRepeat("play", moonlight, "Guys ever do uh jump jam at school?"));
        assertTrue(musicRepeat("play", moonlight, "I'm Jim. No."));
        assertTrue(musicRepeat("play", "Machine Gun Kelly", "Just saw it on YouTube."));
        assertTrue(musicRepeat("play", "mariachi songs", "Oh, I think these guys are trying to kill me."));
        assertFalse(musicRepeat("play", moonlight, "Uh, Gunabot, can you add to the cue, don't blame it on the moonlight?"));
        assertFalse(musicRepeat("play", "Machine Gun Kelly", "A gunnerbot, play machine gun Kelly."));
        assertFalse(musicRepeat("play", "funky tunes", "Gunabot, play us some funky tunes."));
        assertFalse(musicRepeat("radio", "BBC World Service", "bot, chuck on the BBC"));
        // "more like this": nothing to check
        assertFalse(musicRepeat("mix", "", "bot, more like this"));
    }

    @Test
    public void skipToNeedsALineAskingToSkip()
    {
        String mgk = "Machine Gun Kelly";
        assertFalse(musicRepeat("skipto", mgk, "Um, Gunnerbot, can you skip all the songs until we get to Machine Gun Kelly?"));
        assertFalse(musicRepeat("skipto", "3", "bot, go to number three"));
        assertFalse(musicRepeat("skipto", "Dreams", "jump to Dreams"));
        // real lines
        assertTrue(musicRepeat("skipto", mgk, "Uh no."));
        assertTrue(musicRepeat("skipto", mgk, "I thought this was machine gun Kelly for a minute there."));
        assertTrue(musicRepeat("skipto", "30", "Can you put on some thirty seconds tomorrow?"));
        assertTrue(musicRepeat("skipto", "30 seconds", "Isn't that kind of weird?"));
        // moving within the song, which this can't do
        assertTrue(musicRepeat("skipto", "thirty seconds", "Can you skip like thirty seconds into this song?"));
    }

    @Test
    public void spotsTheSameMusicCommandAgain()
    {
        assertTrue(VoiceRouter.sameAsLast("play", "Dreams", "play", "dreams", 5_000));
        assertFalse(VoiceRouter.sameAsLast("play", "Dreams", "play", "dreams", 120_000));
        assertFalse(VoiceRouter.sameAsLast("play", "Toxic", "play", "dreams", 5_000));
        assertFalse(VoiceRouter.sameAsLast("skip", "", "skip", "", 5_000));
        assertFalse(VoiceRouter.sameAsLast("play", "Dreams", null, null, 5_000));
    }

    @Test
    public void crudePersonaKeepsItsLimits()
    {
        String prompt = com.typesafe.config.ConfigFactory.parseResources("reference.conf").getString("crudellmprompt");
        assertTrue(prompt.contains("Nothing sexual, ever"));
        assertTrue(prompt.contains("No slurs"));
        assertTrue(prompt.contains("drop the act"));
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
