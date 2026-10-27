package com.jagrosh.jmusicbot.voice;

import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceRouterTest
{
    @Test
    public void parsesRepeat()
    {
        VoiceRouter.Route r = VoiceRouter.parse("{\"to_bot\": true, \"command\": \"repeat\", \"args\": \"\"}");
        assertTrue(r.toBot);
        assertEquals("repeat", r.command);
    }

    @Test
    public void parsesARoute()
    {
        VoiceRouter.Route r = VoiceRouter.parse("{\"to_bot\": true, \"command\": \"radio\", \"args\": \"RNZ National\"}");
        assertTrue(r.toBot);
        assertEquals("radio", r.command);
        assertEquals("RNZ National", r.args);
    }

    @Test
    public void ignoresAnEmptyThinkBlock()
    {
        VoiceRouter.Route r = VoiceRouter.parse("<think>\n\n</think>\n{\"to_bot\": true, \"command\": \"skip\", \"args\": \"\"}");
        assertTrue(r.toBot);
        assertEquals("skip", r.command);
    }

    @Test
    public void notForTheBotMeansNoCommand()
    {
        // small models sometimes fill in a command even when it isn't for the bot
        VoiceRouter.Route r = VoiceRouter.parse("{\"to_bot\": false, \"command\": \"skip\", \"args\": \"lunch\"}");
        assertFalse(r.toBot);
        assertEquals("none", r.command);
    }

    @Test
    public void noneIsNeverForTheBot()
    {
        assertFalse(VoiceRouter.parse("{\"to_bot\": true, \"command\": \"none\", \"args\": \"\"}").toBot);
    }

    @Test
    public void unknownCommandBecomesChat()
    {
        VoiceRouter.Route r = VoiceRouter.parse("{\"to_bot\": true, \"command\": \"nowplaying\", \"args\": \"\"}");
        assertTrue(r.toBot);
        assertEquals("chat", r.command);
    }

    @Test
    public void garbageIsNotForTheBot()
    {
        assertFalse(VoiceRouter.parse("I think so").toBot);
        assertFalse(VoiceRouter.parse("{\"to_bot\": tru").toBot);
    }

    @Test
    public void errorRepliesBecomeSpeakable()
    {
        assertEquals("No results found for xyz!", VoiceManager.spokenReply("❌ No results found for `xyz`!"));
        assertEquals("You must be listening in to use that!", VoiceManager.spokenReply("❌ You must be listening in <#123456> to use that!").replace("  ", " "));
    }
}
