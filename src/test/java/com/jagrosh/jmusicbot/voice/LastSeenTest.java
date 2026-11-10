package com.jagrosh.jmusicbot.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
