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
    public void plainTextIsAChatReply()
    {
        LlmClient.Decision d = LlmClient.parseDecision("Sure thing, mate.");
        assertEquals(Type.CHAT, d.action);
        assertEquals("Sure thing, mate.", d.reply);
        assertEquals(Type.CHAT, LlmClient.parseDecision("{not json").action);
    }
}
