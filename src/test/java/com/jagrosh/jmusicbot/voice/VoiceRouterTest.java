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
    public void skipToKeepsItsSong()
    {
        VoiceRouter.Route r = VoiceRouter.parse("{\"to_bot\": true, \"command\": \"skipto\", \"args\": \"Dreams\"}");
        assertEquals("skipto", r.command);
        assertEquals("Dreams", r.args);
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

    private static org.json.JSONObject decision(double toBot, String choice, double sure, double none)
    {
        return new org.json.JSONObject().put("answers", new org.json.JSONObject()
                .put("to_bot", new org.json.JSONObject().put("type", "noul").put("noul", toBot))
                .put("command", new org.json.JSONObject().put("type", "choice").put("choice", choice).put("confidence", sure)
                        .put("probabilities", new org.json.JSONObject().put(choice, sure).put("none", none))));
    }

    @Test
    public void aDecisionIsScored()
    {
        VoiceRouter.Route r = VoiceRouter.parseDecision(decision(0.96, "skip", 0.9, 0.02));
        assertTrue(r.toBot);
        assertTrue(r.scored);
        assertEquals("skip", r.command);
        assertEquals(0.97, r.confidence, 0.001);
    }

    @Test
    public void theTwoAnswersAreAveraged()
    {
        // "I wasn't talking to you": plainly not_you, and plainly not talking to the bot
        VoiceRouter.Route r = VoiceRouter.parseDecision(decision(0.13, "not_you", 0.99, 0.01));
        assertEquals("not_you", r.command);
        assertEquals(0.56, r.confidence, 0.001);
        assertTrue(r.toBot);
    }

    @Test
    public void anUnsureCommandIsTalkedOver()
    {
        assertEquals("chat", VoiceRouter.parseDecision(decision(0.9, "mix", 0.4, 0.05)).command);
        assertEquals("chat", VoiceRouter.parseDecision(decision(0.9, "nowplaying", 0.9, 0.05)).command);
    }

    @Test
    public void noneFromADecisionIsNotForTheBot()
    {
        VoiceRouter.Route r = VoiceRouter.parseDecision(decision(0.05, "none", 0.95, 0.95));
        assertFalse(r.toBot);
        assertEquals(0.05, r.confidence, 0.001);
    }

    @Test
    public void aScoreCanOutweighTheName()
    {
        // "Wait, are you still going bot though?"
        VoiceRouter.Route lane = VoiceRouter.parseDecision(decision(0.1, "none", 0.8, 0.8));
        assertFalse(VoiceRouter.forBot(lane, true, false));
        // a yes or no can't: the name wins, as it does when the router is down
        assertTrue(VoiceRouter.forBot(VoiceRouter.parse("{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}"), true, false));
        assertTrue(VoiceRouter.forBot(null, true, false));
        assertFalse(VoiceRouter.forBot(null, false, true));
    }

    @Test
    public void anUnpromptedLineNeedsMore()
    {
        VoiceRouter.Route r = VoiceRouter.parseDecision(decision(0.5, "chat", 0.6, 0.4));
        assertEquals(0.55, r.confidence, 0.001);
        assertTrue(VoiceRouter.forBot(r, true, false));
        assertTrue(VoiceRouter.forBot(r, false, true));
        assertFalse(VoiceRouter.forBot(r, false, false));
    }

    @Test
    public void volumeSumsAreDoneInCode()
    {
        String player = "Now playing: Dreams by Fleetwood Mac. Volume 30.";
        assertEquals("50", VoiceRouter.checkArgs("volume", "+20", player));
        assertEquals("10", VoiceRouter.checkArgs("volume", "-20", player));
        assertEquals("0", VoiceRouter.checkArgs("volume", "-40", player));
        assertEquals("150", VoiceRouter.checkArgs("volume", "+140", player));
        assertEquals("75", VoiceRouter.checkArgs("volume", "75", player));
        assertEquals("5", VoiceRouter.checkArgs("volume", "-25%", player));
        assertNull(VoiceRouter.checkArgs("volume", "up a bit", player));
        assertNull(VoiceRouter.checkArgs("volume", "500", player));
        assertNull(VoiceRouter.checkArgs("volume", "+20", "Nothing is playing."));
    }

    @Test
    public void aCommandNeedsItsSong()
    {
        assertNull(VoiceRouter.checkArgs("play", "", "Volume 100."));
        assertEquals("Dreams", VoiceRouter.checkArgs("play", "Dreams", "Volume 100."));
        // a mix with nothing named is of what's playing
        assertEquals("", VoiceRouter.checkArgs("mix", "", "Volume 100."));
    }

    @Test
    public void theDecisionRequestKeepsItsOrder()
    {
        VoiceRouter router = new VoiceRouter("https://openrouter.ai/api", "key", "google/gemma-3-12b-it", "google/gemini-decision-1");
        String body = router.decisionRequest(java.util.List.of("Sam: who's mid", "Regan: bot, play \"Dreams\""), false,
                "Nothing is playing. Volume 100.", null, false);
        org.json.JSONObject o = new org.json.JSONObject(body);
        assertEquals("google/gemini-decision-1", o.getString("model"));
        assertEquals("Regan: bot, play \"Dreams\"", o.getJSONObject("state").getString("last_line"));
        assertEquals("Sam: who's mid", o.getJSONObject("state").getJSONArray("earlier_lines").getString(0));
        assertEquals("off", o.getJSONObject("state").getString("crude_mode"));
        org.json.JSONObject choices = o.getJSONObject("questions").getJSONObject("command").getJSONObject("criteria");
        assertTrue(choices.has("crude_on"));
        // explicit mode isn't available here, so it isn't offered
        assertFalse(choices.has("explicit_on"));
        assertFalse(o.getJSONObject("state").has("explicit_mode"));
        // chat and none are offered last
        assertTrue(body.indexOf("\"play\":") < body.indexOf("\"crude_on\":"));
        assertTrue(body.indexOf("\"crude_off\":") < body.indexOf("\"chat\":"));
        assertTrue(body.indexOf("\"chat\":") < body.indexOf("\"none\":"));
    }

    @Test
    public void aDecisionModelNeedsOpenRouter()
    {
        assertEquals("qwen", new VoiceRouter("http://192.168.0.147:1235", "", "qwen", "google/gemini-decision-1").describe());
        assertEquals("google/gemma-3-12b-it", new VoiceRouter("https://openrouter.ai/api", "k", "google/gemma-3-12b-it", "").describe());
    }
}
