package com.jagrosh.jmusicbot.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class LastSeenTest
{
    @Test
    public void findsByNameAndKeepsBothKinds() throws Exception
    {
        Path file = Files.createTempDirectory("lastseen").resolve("last-seen.json");
        LastSeen seen = new LastSeen(file);
        seen.message(1, "Regan", 1000);
        seen.voice(1, "Regan", 5000);
        LastSeen.Seen found = seen.byName("reg");
        assertEquals(1000, found.messageAt());
        assertEquals(5000, found.voiceAt());
        assertEquals(5000, found.latest());
        assertNull(seen.byName("nobody"));
        assertNull(seen.byName(" "));
    }

    @Test
    public void survivesReloadAndForget() throws Exception
    {
        Path file = Files.createTempDirectory("lastseen").resolve("last-seen.json");
        LastSeen seen = new LastSeen(file);
        seen.message(2, "Sam", 100_000); // first sighting is always saved
        assertEquals("Sam", new LastSeen(file).byId(2).name());
        seen.forget(2);
        assertNull(new LastSeen(file).byId(2));
    }

    @Test
    public void formatsAgo()
    {
        assertEquals("just now", LastSeen.ago(30_000));
        assertEquals("1 minute ago", LastSeen.ago(60_000));
        assertEquals("3 hours ago", LastSeen.ago(3 * 3_600_000L));
        assertEquals("2 days ago", LastSeen.ago(2 * 86_400_000L));
    }

    @Test
    public void steadyChatterIsStillSaved() throws Exception
    {
        java.nio.file.Path file = java.nio.file.Files.createTempDirectory("lastseen").resolve("last-seen.json");
        LastSeen seen = new LastSeen(file);
        long t = 1_000_000_000_000L;
        // a message every 20 seconds for five minutes: never a minute apart
        for (int i = 0; i <= 15; i++)
            seen.message(1, "Sam", t + i * 20_000L);
        LastSeen reloaded = new LastSeen(file);
        assertNotNull(reloaded.byId(1));
        assertTrue("saved within the last minute of activity", reloaded.byId(1).messageAt() >= t + 240_000L);
    }

    @Test
    public void describesWhoHasBeenAroundForTheLlm() throws Exception
    {
        java.nio.file.Path file = java.nio.file.Files.createTempDirectory("lastseen").resolve("last-seen.json");
        LastSeen seen = new LastSeen(file);
        long now = 1_000_000_000_000L;
        seen.message(1, "gunna", now - 3 * 3600_000L);
        seen.voice(2, "Sam", now - 2 * 86_400_000L);
        seen.voice(3, "Devon", now - 60_000L);
        assertEquals("gunna 3 hours ago, Sam 2 days ago", seen.describe(now, java.util.List.of(3L), 5));
        assertEquals("Devon 1 minute ago", seen.describe(now, java.util.List.of(), 1));
        assertEquals("", new LastSeen(file.resolveSibling("none.json")).describe(now, java.util.List.of(), 5));
    }
}
