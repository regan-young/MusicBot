package com.jagrosh.jmusicbot.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

import static org.junit.Assert.*;

public class ReplyStreamTest
{
    /** Feeds the text in small pieces, the way a model streams tokens. */
    private static List<String> stream(String json, int pieceLength)
    {
        List<String> out = new ArrayList<>();
        ReplyStream r = new ReplyStream(out::add);
        for (int i = 0; i < json.length(); i += pieceLength)
            r.feed(json.substring(i, Math.min(json.length(), i + pieceLength)));
        r.finish();
        assertEquals(json, r.content());
        return out;
    }

    @Test
    public void chatReplyStreamsBySentence()
    {
        String json = "{\"action\": \"chat\", \"query\": \"\", \"reply\": \"Quantum mechanics deals with the very small. "
                + "It says you can't know both position and momentum at once! Weird, isn't it?\"}";
        for (int piece : new int[] { 1, 3, 7, 500 })
            assertEquals(List.of("Quantum mechanics deals with the very small.",
                    "It says you can't know both position and momentum at once!", "Weird, isn't it?"), stream(json, piece));
    }

    @Test
    public void escapesAndShortSentences()
    {
        String json = "{\"action\":\"chat\",\"query\":\"\",\"reply\":\"Ah. She said \\\"hello\\\" to Mr. Brightside \\u2014 twice.\"}";
        assertEquals(List.of("Ah. She said \"hello\" to Mr. Brightside — twice."), stream(json, 2));
    }

    @Test
    public void commandsDontStream()
    {
        assertEquals(List.of(), stream("{\"action\": \"play\", \"query\": \"Six60\", \"reply\": \"Queuing Six60. Enjoy it.\"}", 4));
        // reply before the action: can't tell in time whether it's chat
        assertEquals(List.of(), stream("{\"reply\": \"Sure thing, here we go now.\", \"action\": \"chat\"}", 4));
    }

    @Test
    public void readsServerSentEvents() throws Exception
    {
        List<String> out = new ArrayList<>();
        ReplyStream r = new ReplyStream(out::add);
        LlmClient.readEvents(Stream.of(
                ": OPENROUTER PROCESSING",
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"action\\\": \\\"chat\\\", \\\"query\\\": \\\"\\\", \"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\"\\\"reply\\\": \\\"Lima is the capital of Peru.\\\"}\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":\"stop\"}]}",
                "data: [DONE]"), r);
        r.finish();
        assertEquals(List.of("Lima is the capital of Peru."), out);
        assertEquals(VoiceCommand.Type.CHAT, LlmClient.parseDecision(r.content()).action);
    }
}
