package com.jagrosh.jmusicbot.voice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class SessionRecorderTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final ZoneId NZ = ZoneId.of("Pacific/Auckland");
    // 2026-11-12 20:31:05.000 in Auckland
    private static final long START = java.time.ZonedDateTime.of(2026, 11, 12, 20, 31, 5, 0, NZ).toInstant().toEpochMilli();

    @Test
    public void offWithoutAFolder()
    {
        assertNull(SessionRecorder.start("", "Guild", "General", NZ, "parakeet"));
        assertNull(SessionRecorder.start(null, "Guild", "General", NZ, "parakeet"));
    }

    @Test
    public void savesASessionToRead() throws Exception
    {
        SessionRecorder r = SessionRecorder.start(tmp.getRoot().toString(), "WannaBET?", "General / Dota", NZ, "parakeet", START);
        Path folder = r.folder();
        assertEquals("2026-11-12_203105_WannaBET_General-Dota", folder.getFileName().toString());

        byte[] wav = VoiceAudio.toWav(new byte[32000]);
        String clip = r.heard(START + 4412, 289943634567233537L, "devon", "Devon", wav, "Gunnerbot, play Dreams", 1000, 210, false);
        assertEquals("clips/203109-412_Devon_289943634567233537.wav", clip);
        r.routed(clip, "play", "Dreams", "", null, true, false, 0);
        r.bot("Gunnabot", "Queuing up Dreams.");
        String echo = r.heard(START + 9000, 1L, "sam", "Sam / KFC", wav, "Queuing up Dreams", 1000, 200, true);
        String quiet = r.heard(START + 9500, 1L, "sam", "Sam", wav, "", 500, 150, false);
        r.routed(echo, "none", "", "", null, false, false, 0);
        r.close();

        assertArrayEquals(wav, Files.readAllBytes(folder.resolve(clip)));
        assertTrue(Files.exists(folder.resolve(echo)));
        assertTrue(Files.exists(folder.resolve(quiet)));

        List<String> read = Files.readAllLines(folder.resolve("transcript.txt"), StandardCharsets.UTF_8);
        assertEquals("[20:31:09] Devon: Gunnerbot, play Dreams", read.get(0));
        assertTrue(read.get(1).endsWith("  -> play [Dreams]"));
        assertTrue(read.get(2).endsWith("] Gunnabot: Queuing up Dreams."));
        assertEquals("[20:31:14] Sam / KFC: Queuing up Dreams  (the bot's own voice, ignored)", read.get(3));
        // nothing heard, nothing to read
        assertEquals(4, read.size());

        List<String> lines = Files.readAllLines(folder.resolve("transcript.jsonl"), StandardCharsets.UTF_8);
        assertEquals(6, lines.size());
        JSONObject heard = new JSONObject(lines.get(0));
        assertEquals("heard", heard.getString("type"));
        assertEquals(clip, heard.getString("clip"));
        assertEquals("289943634567233537", heard.getString("user_id"));
        assertEquals("devon", heard.getString("username"));
        assertEquals("Gunnerbot, play Dreams", heard.getString("text"));
        assertEquals("parakeet", heard.getString("stt_model"));
        assertEquals("2026-11-12T20:31:09.412+13:00", heard.getString("time"));
        JSONObject routed = new JSONObject(lines.get(1));
        assertEquals("routed", routed.getString("type"));
        assertEquals(clip, routed.getString("clip"));
        assertTrue(routed.getBoolean("for_bot"));
        assertEquals("Dreams", routed.getString("args"));
        assertEquals("bot", new JSONObject(lines.get(2)).getString("type"));
        assertTrue(new JSONObject(lines.get(3)).getBoolean("echo"));
        assertEquals("", new JSONObject(lines.get(4)).getString("text"));

        JSONObject session = new JSONObject(Files.readString(folder.resolve("session.json")));
        assertEquals("WannaBET?", session.getString("guild"));
        assertEquals("2026-11-12T20:31:05+13:00", session.getString("started"));
        assertTrue(session.has("ended"));
        JSONObject sam = session.getJSONObject("speakers").getJSONObject("1");
        assertEquals(2, sam.getInt("clips"));
        assertEquals(1500, sam.getInt("audio_ms"));
        assertEquals(2, sam.getJSONArray("names").length());
    }

    @Test
    public void aScoredRouteKeepsItsConfidence() throws Exception
    {
        SessionRecorder r = SessionRecorder.start(tmp.getRoot().toString(), "G", "C", NZ, "parakeet", START);
        r.routed("clips/x.wav", "none", "", "", new VoiceRouter.Route(false, "none", "", 0.1234, true), true, false, 700);
        r.close();
        JSONObject routed = new JSONObject(Files.readAllLines(r.folder().resolve("transcript.jsonl")).get(0));
        assertEquals(0.123, routed.getDouble("confidence"), 0.0001);
        assertEquals(700, routed.getInt("router_ms"));
        assertFalse(routed.getBoolean("for_bot"));
        assertTrue(routed.getBoolean("named"));
    }

    @Test
    public void namesAreSafeInFileNames()
    {
        assertEquals("España", SessionRecorder.safe("España"));
        assertEquals("C-Hi", SessionRecorder.safe("C-Hi"));
        assertEquals("a-b", SessionRecorder.safe("../a/b\\"));
        assertEquals("unknown", SessionRecorder.safe("😀"));
        assertEquals("unknown", SessionRecorder.safe(null));
    }
}
