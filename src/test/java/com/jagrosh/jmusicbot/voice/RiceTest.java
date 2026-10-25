package com.jagrosh.jmusicbot.voice;

import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.voice.VoiceCommand.Type;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class RiceTest
{
    @Test
    public void specialLines()
    {
        // prices come from Postgres as strings
        assertEquals("New World: Beard Brothers Venison Sausages 500g $9.99 (was $12.69, 21% off) [Clubcard]",
                Rice.line(new JSONObject("{\"store\":\"newworld\",\"name\":\"Beard Brothers Venison Sausages 500g\","
                        + "\"sale_price\":\"9.99\",\"original_price\":\"12.69\",\"unit\":\"ea\",\"promo_label\":\"Clubcard\"}")));
        assertEquals("Pak'nSave: Pams Whole Chicken Large 1.9kg $12.79",
                Rice.line(new JSONObject("{\"store\":\"paknsave\",\"name\":\"Pams Whole Chicken Large 1.9kg\","
                        + "\"sale_price\":\"12.79\",\"original_price\":null,\"unit\":\"ea\",\"promo_label\":null}")));
        assertEquals("Woolworths: Beef Mince $14.00/kg",
                Rice.line(new JSONObject("{\"store\":\"woolworths\",\"name\":\"Beef Mince\",\"sale_price\":14,\"unit\":\"kg\"}")));
        assertEquals(2, Rice.lines(new JSONArray("[{\"store\":\"paknsave\",\"name\":\"a\"},{\"store\":\"paknsave\",\"name\":\"b\"}]"))
                .split("\n").length);
    }

    @Test
    public void frontPageTrustsOnlyCloseMatches()
    {
        String fp = Rice.frontPage(new JSONObject("{\"catalogue\":{\"title\":\"19th - 25th October\",\"viewerUrl\":\"https://x\"},"
                + "\"items\":[{\"name\":\"Lisa's Hummus 200g\",\"match\":{\"sale_price\":\"5.29\",\"score\":0.62}},"
                + "{\"name\":\"Fresh Salmon\",\"match\":{\"sale_price\":\"40\",\"score\":0.3}},{\"name\":\"Bananas\"}]}"));
        assertTrue(fp.contains("- Lisa's Hummus 200g about $5.29\n"));
        assertTrue(fp.contains("- Fresh Salmon\n"));
        assertTrue(fp.contains("- Bananas\n"));
        assertTrue(fp.contains("Mailer: https://x"));
    }

    @Test
    public void parsesSpecialsAction()
    {
        LlmClient.Decision d = LlmClient.parseDecision("{\"action\":\"specials\",\"query\":\"chicken thighs\",\"task\":\"\","
                + "\"reply\":\"Let me check.\"}");
        assertEquals(Type.SPECIALS, d.action);
        assertEquals("chicken thighs", d.query);
    }

    @Test
    public void specialsAnswerFallsBackToPlainText()
    {
        JSONObject o = VoiceManager.parseSpecialsAnswer("{\"say\":\"Chicken's cheap.\",\"post\":\"- Pak'nSave: chicken $6\"}");
        assertEquals("Chicken's cheap.", o.getString("say"));
        assertEquals("- Pak'nSave: chicken $6", o.getString("post"));
        JSONObject plain = VoiceManager.parseSpecialsAnswer("Chicken is cheap at Pak'nSave.");
        assertEquals("Chicken is cheap at Pak'nSave.", plain.getString("say"));
        assertEquals("Chicken is cheap at Pak'nSave.", plain.getString("post"));
    }

    @Test
    public void historyForPlayItAgain()
    {
        long now = 10 * 60_000;
        String h = VoiceManager.historyContext(List.of(
                new AudioHandler.Played("Dreams", "Fleetwood Mac", "https://youtu.be/a", "regan", false, now),
                new AudioHandler.Played("RNZ National", null, "https://radio/rnz", null, true, now - 5 * 60_000)), now);
        assertTrue(h.contains("1. Dreams by Fleetwood Mac (asked for by regan, just now) https://youtu.be/a"));
        assertTrue(h.contains("2. radio station RNZ National (5 min ago) https://radio/rnz"));
        assertEquals("", VoiceManager.historyContext(List.of(), now));
    }
}
