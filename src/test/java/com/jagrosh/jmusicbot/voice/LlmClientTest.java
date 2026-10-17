package com.jagrosh.jmusicbot.voice;

import com.jagrosh.jmusicbot.voice.VoiceCommand.Type;
import org.junit.Test;

import static org.junit.Assert.*;

public class LlmClientTest
{
    @Test
    public void parsesPlay()
    {
        LlmClient.Decision d = LlmClient.parseDecision("{\"action\":\"play\",\"query\":\"Fleetwood Mac Dreams\",\"reply\":\"On it.\"}");
        assertEquals(Type.PLAY, d.action);
        assertEquals("Fleetwood Mac Dreams", d.query);
        assertEquals("On it.", d.reply);
    }

    @Test
    public void playWithoutQueryResumes()
    {
        assertEquals(Type.RESUME, LlmClient.parseDecision("{\"action\":\"play\",\"query\":\"\",\"reply\":\"\"}").action);
    }

    @Test
    public void toleratesCodeFencesAndCase()
    {
        LlmClient.Decision d = LlmClient.parseDecision("```json\n{\"action\": \"SKIP\", \"reply\": \"Skipping.\"}\n```");
        assertEquals(Type.SKIP, d.action);
        assertEquals("Skipping.", d.reply);
    }

    @Test
    public void unknownActionIsChat()
    {
        assertEquals(Type.CHAT, LlmClient.parseDecision("{\"action\":\"dance\",\"reply\":\"No.\"}").action);
    }

    @Test
    public void parsesResearchAndPost()
    {
        LlmClient.Decision d = LlmClient.parseDecision("{\"action\":\"research\",\"query\":\"\",\"task\":\"Find the score of "
                + "last night's All Blacks test\",\"post\":\"\",\"reply\":\"Let me look that up.\"}");
        assertEquals(Type.RESEARCH, d.action);
        assertEquals("Find the score of last night's All Blacks test", d.task);
        assertEquals("Let me look that up.", d.reply);
        // research with nothing to research is just talk
        assertEquals(Type.CHAT, LlmClient.parseDecision("{\"action\":\"research\",\"task\":\"\",\"reply\":\"Hmm.\"}").action);
        LlmClient.Decision p = LlmClient.parseDecision("{\"action\":\"chat\",\"post\":\"https://example.com\",\"reply\":\"Posted.\"}");
        assertEquals("https://example.com", p.post);
        assertEquals("", p.task);
    }

    @Test
    public void researchCitations()
    {
        org.json.JSONObject m = new org.json.JSONObject("{\"content\":\"x\",\"annotations\":[{\"type\":\"url_citation\","
                + "\"url_citation\":{\"url\":\"https://www.bbc.com/sport\",\"title\":\"BBC\"}},{\"type\":\"other\"}]}");
        assertEquals(java.util.List.of("https://www.bbc.com/sport"), Researcher.citations(m));
        assertEquals(java.util.List.of(), Researcher.citations(new org.json.JSONObject("{\"content\":\"x\"}")));
    }

    @Test
    public void spotsClaimsOfPosting()
    {
        assertTrue(Researcher.CLAIMS_POST.matcher("I've posted the full results in the chat.").find());
        assertTrue(Researcher.CLAIMS_POST.matcher("I've dropped the details in the text channel.").find());
        assertFalse(Researcher.CLAIMS_POST.matcher("They put on a great show. Chat soon.").find());
        assertFalse(Researcher.CLAIMS_POST.matcher("The All Blacks won 43-21.").find());
    }

    @Test
    public void postOnlyIsNotResume()
    {
        assertEquals(Type.CHAT, LlmClient.parseDecision("{\"action\":\"play\",\"query\":\"\",\"post\":\"https://x.org\",\"reply\":\"Posted.\"}").action);
    }

    @Test
    public void plainTextIsAChatReply()
    {
        LlmClient.Decision d = LlmClient.parseDecision("Sure thing, mate.");
        assertEquals(Type.CHAT, d.action);
        assertEquals("Sure thing, mate.", d.reply);
        assertEquals(Type.CHAT, LlmClient.parseDecision("{not json").action);
    }
}
