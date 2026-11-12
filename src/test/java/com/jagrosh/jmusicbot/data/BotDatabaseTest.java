package com.jagrosh.jmusicbot.data;

import java.time.ZoneId;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class BotDatabaseTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long GUILD = 1, SAM = 10, DEVON = 20;
    private static final ZoneId NZ = ZoneId.of("Pacific/Auckland");
    // Thursday 12 November 2026, 8 pm in Auckland
    private static final long NOW = java.time.ZonedDateTime.of(2026, 11, 12, 20, 0, 0, 0, NZ).toInstant().toEpochMilli();
    private static final long MIN = 60_000, DAY = 86_400_000L;

    private BotDatabase db;
    private String file;

    @Before
    public void open() throws Exception
    {
        file = tmp.newFolder().toPath().resolve("gunnabot.db").toString();
        db = BotDatabase.open(file);
        assertNotNull(db);
    }

    @After
    public void close()
    {
        db.close();
    }

    /** Plays a four-minute song for this long, ending the way given. */
    private void play(String title, String author, long who, String name, long at, long playedMs, String ended)
    {
        long id = db.nextId();
        db.playStarted(id, GUILD, new BotDatabase.Play(title, author, "https://y/" + title.replace(' ', '_'), false, 4 * MIN, who, name, at));
        db.playEnded(id, ended, playedMs, at + playedMs);
    }

    @Test
    public void noPathMeansNoDatabase()
    {
        assertNull(BotDatabase.open(""));
        assertNull(BotDatabase.open(null));
    }

    @Test
    public void recentPlaysSurviveAReopen()
    {
        play("Dreams", "Fleetwood Mac", SAM, "sam", NOW - 3 * MIN * 10, 4 * MIN, "finished");
        play("Africa", "Toto", DEVON, "devon", NOW - 2 * MIN * 10, 4 * MIN, "finished");
        play("Dreams", "Fleetwood Mac", DEVON, "devon", NOW - MIN * 10, 4 * MIN, "finished");
        db.close();
        db = BotDatabase.open(file);

        List<BotDatabase.Play> recent = db.recentPlays(GUILD, 20);
        // each track once, newest first
        assertEquals(2, recent.size());
        assertEquals("Dreams", recent.get(0).title());
        assertEquals("devon", recent.get(0).requester());
        assertEquals(NOW - MIN * 10, recent.get(0).startedAt());
        assertEquals("Africa", recent.get(1).title());
        assertTrue(db.recentPlays(2, 20).isEmpty());
    }

    @Test
    public void countsWhatIsPlayedMostAndByWhom()
    {
        for (int i = 0; i < 3; i++)
            play("Dreams", "Fleetwood Mac", SAM, "sam", NOW - (i + 1) * DAY, 4 * MIN, "finished");
        play("Africa", "Toto", DEVON, "devon", NOW - DAY, 4 * MIN, "finished");
        play("Africa", "Toto", DEVON, "devon", NOW - 2 * DAY, 4 * MIN, "finished");
        play("Old One", "Someone", SAM, "sam", NOW - 200 * DAY, 4 * MIN, "finished");
        db.flush();

        List<BotDatabase.Count> top = db.mostPlayed(GUILD, NOW - 90 * DAY, 0, 2, 8);
        assertEquals(2, top.size());
        assertEquals(new BotDatabase.Count("Dreams", "Fleetwood Mac", 3), top.get(0));
        assertEquals(new BotDatabase.Count("Africa", "Toto", 2), top.get(1));
        // one person's, with the old one once the window is all time
        assertEquals(List.of(new BotDatabase.Count("Africa", "Toto", 2)), db.mostPlayed(GUILD, NOW - 90 * DAY, DEVON, 1, 5));
        assertEquals(2, db.mostPlayed(GUILD, 0, SAM, 1, 5).size());
    }

    @Test
    public void aSongStoppedEarlyIsCutShortAndOneHeardOutIsNot()
    {
        play("Nickelback Song", "Nickelback", SAM, "sam", NOW - 3 * DAY, 20_000, "stopped");
        play("Nickelback Song", "Nickelback", SAM, "sam", NOW - 2 * DAY, 30_000, "replaced");
        // skipped once only: not a pattern
        play("Africa", "Toto", SAM, "sam", NOW - 2 * DAY, 30_000, "stopped");
        // stopped near the end, and played right through
        play("Dreams", "Fleetwood Mac", SAM, "sam", NOW - DAY, 3 * MIN, "stopped");
        play("Dreams", "Fleetwood Mac", SAM, "sam", NOW - DAY, 4 * MIN, "finished");
        db.flush();
        assertEquals(List.of(new BotDatabase.Count("Nickelback Song", "Nickelback", 2)), db.mostCutShort(GUILD, 0, 5));
    }

    @Test
    public void radioIsRememberedButNotCounted()
    {
        long id = db.nextId();
        db.playStarted(id, GUILD, new BotDatabase.Play("RNZ National", null, "https://radio/rnz", true, Long.MAX_VALUE, SAM, "sam", NOW));
        db.playEnded(id, "stopped", 60_000, NOW + 60_000);
        db.flush();
        assertEquals("RNZ National", db.recentPlays(GUILD, 5).get(0).title());
        assertTrue(db.recentPlays(GUILD, 5).get(0).radio());
        assertTrue(db.mostPlayed(GUILD, 0, 0, 1, 5).isEmpty());
        assertTrue(db.mostCutShort(GUILD, 0, 5).isEmpty());
    }

    @Test
    public void theDigestAnswersWhoPlayedWhatAndWhen()
    {
        assertEquals("Nothing has been played since the history began.", db.musicDigest(GUILD, NOW, NZ, 50));
        play("Dreams", "Fleetwood Mac", SAM, "sam", NOW - 6 * DAY, 4 * MIN, "finished");
        play("Dreams", "Fleetwood Mac", SAM, "sam", NOW - DAY, 4 * MIN, "finished");
        play("Africa", "Toto", DEVON, "devon", NOW - 30 * MIN, 20_000, "stopped");
        db.flush();
        String digest = db.musicDigest(GUILD, NOW, NZ, 50);
        assertTrue(digest, digest.contains("3 plays since Fri 6 Nov 2026. Today is Thu 12 Nov 2026."));
        assertTrue(digest, digest.contains("- Dreams by Fleetwood Mac (2)"));
        assertTrue(digest, digest.contains("- sam (2 plays): Dreams by Fleetwood Mac x2"));
        assertTrue(digest, digest.contains("- Thu 12 Nov, 7:30 PM: Africa by Toto, asked for by devon, cut short"));
        assertTrue(digest, digest.contains("- Wed 11 Nov, 8:00 PM: Dreams by Fleetwood Mac, asked for by sam\n"));
    }

    @Test
    public void aLineIsKeptWithWhatTheBotMadeOfIt() throws Exception
    {
        long id = db.nextId();
        db.lineHeard(id, GUILD, 99, NOW, DEVON, "Devon", "Gunnerbot, play Dreams", 1800, 210, "parakeet", "clips/a.wav", false);
        db.lineRouted(id, "play", "Dreams", "", true, false, 0.97, 640L);
        long quiet = db.nextId();
        db.lineHeard(quiet, GUILD, 99, NOW + 1000, SAM, "Sam", "he's going bot", 900, 190, "parakeet", null, false);
        db.lineRouted(quiet, "none", "", "", true, false, 0.08, 600L);
        db.botLine(GUILD, 99, NOW + 2000, "Queuing up Dreams.");
        db.correction(GUILD, NOW + 5000, id, DEVON, "Devon", "Gunnerbot, play Dreams", "play", "Dreams", "Sam", "not you, bot", "[\"a\"]");
        db.flush();

        assertEquals(1, db.number("SELECT COUNT(*) FROM voice_lines WHERE outcome = 'play' AND args = 'Dreams' AND named = 1 AND confidence > 0.9"));
        assertEquals(1, db.number("SELECT COUNT(*) FROM voice_lines WHERE outcome = 'none' AND args IS NULL"));
        assertEquals(1, db.number("SELECT COUNT(*) FROM bot_lines WHERE session = 99"));
        // the day's view of the router: two lines, one for the bot, one told off
        assertEquals(2, db.number("SELECT lines FROM router_daily"));
        assertEquals(1, db.number("SELECT for_bot FROM router_daily"));
        assertEquals(1, db.number("SELECT commands FROM router_daily"));
        assertEquals(1, db.number("SELECT corrections FROM router_daily"));
        assertEquals(620, db.number("SELECT avg_router_ms FROM router_daily"));
        assertEquals(1, db.number("SELECT lines FROM stt_by_person WHERE user_id = ?", DEVON));
    }

    @Test
    public void forgettingSomeoneRemovesTheirWordsAndTheirNameFromPlays() throws Exception
    {
        play("Dreams", "Fleetwood Mac", SAM, "sam", NOW - DAY, 4 * MIN, "finished");
        play("Africa", "Toto", DEVON, "devon", NOW - DAY, 4 * MIN, "finished");
        long id = db.nextId();
        db.lineHeard(id, GUILD, 99, NOW, SAM, "Sam", "something private", 900, 190, "parakeet", null, false);
        db.lineHeard(db.nextId(), GUILD, 99, NOW, DEVON, "Devon", "hello", 900, 190, "parakeet", null, false);
        db.correction(GUILD, NOW, id, SAM, "Sam", "something private", "chat", "", "Devon", "not you", "[]");
        db.codeRequested("task-1", "change", NOW, SAM, "Sam", "add a thing");
        db.forget(SAM);
        db.flush();

        assertEquals(0, db.number("SELECT COUNT(*) FROM voice_lines WHERE user_id = ?", SAM));
        assertEquals(1, db.number("SELECT COUNT(*) FROM voice_lines"));
        assertEquals(0, db.number("SELECT COUNT(*) FROM corrections"));
        // the song still counts for the server, with nobody's name on it
        assertEquals(2, db.number("SELECT COUNT(*) FROM plays"));
        assertEquals(0, db.number("SELECT COUNT(*) FROM plays WHERE requester_id = ? OR requester = 'sam'", SAM));
        assertEquals(1, db.number("SELECT COUNT(*) FROM plays WHERE requester = 'devon'"));
        assertEquals(1, db.number("SELECT COUNT(*) FROM code_requests WHERE requester IS NULL"));
    }

    @Test
    public void oldSpeechIsDroppedAndPlaysAreKept() throws Exception
    {
        play("Dreams", "Fleetwood Mac", SAM, "sam", NOW - 40 * DAY, 4 * MIN, "finished");
        db.lineHeard(db.nextId(), GUILD, 1, NOW - 40 * DAY, SAM, "Sam", "old", 900, 190, "parakeet", null, false);
        db.lineHeard(db.nextId(), GUILD, 2, NOW - DAY, SAM, "Sam", "new", 900, 190, "parakeet", null, false);
        db.botLine(GUILD, 1, NOW - 40 * DAY, "old reply");
        db.forgetSpeechBefore(NOW - 30 * DAY);
        db.flush();
        assertEquals(1, db.number("SELECT COUNT(*) FROM voice_lines"));
        assertEquals(1, db.number("SELECT COUNT(*) FROM voice_lines WHERE text = 'new'"));
        assertEquals(0, db.number("SELECT COUNT(*) FROM bot_lines"));
        assertEquals(1, db.number("SELECT COUNT(*) FROM plays"));
    }

    @Test
    public void aCodeRequestIsFollowedToItsEnd() throws Exception
    {
        db.codeRequested("task-1", "change", NOW, DEVON, "Devon", "announce people joining");
        db.codeUpdated("task-1", NOW + 1000, "running", null, null, null);
        db.codeUpdated("task-1", NOW + 9000, "deploying", "Added a join announcement.", "", "abc123");
        db.codeUpdated("task-1", NOW + 20_000, "deployed", "", "", "");
        db.flush();
        assertEquals(1, db.number("SELECT COUNT(*) FROM code_requests WHERE state = 'deployed' AND summary = 'Added a join announcement.' "
                + "AND commit_sha = 'abc123' AND error IS NULL AND updated_at = ?", NOW + 20_000));
    }

    @Test
    public void theConversationReadsInOrderWithTheBotInIt()
    {
        db.lineHeard(db.nextId(), GUILD, 99, NOW, DEVON, "Devon", "so I said to him, no way", 900, 190, "parakeet", null, false);
        db.lineHeard(db.nextId(), GUILD, 99, NOW + 2000, SAM, "Sam", "", 400, 150, "parakeet", null, false);
        db.lineHeard(db.nextId(), GUILD, 99, NOW + 3000, SAM, "Sam", "Queuing up Dreams", 900, 190, "parakeet", null, true);
        db.botLine(GUILD, 99, NOW + 4000, "Sounds like quite the story.");
        db.lineHeard(db.nextId(), GUILD, 99, NOW + 60_000, SAM, "Sam", "what happened next", 900, 190, "parakeet", null, false);
        db.lineHeard(db.nextId(), GUILD, 98, NOW, SAM, "Sam", "another night", 900, 190, "parakeet", null, false);
        db.lineHeard(db.nextId(), GUILD, 99, NOW - 2 * 3600_000, SAM, "Sam", "too long ago", 900, 190, "parakeet", null, false);
        db.flush();
        assertEquals("[8:00 PM] Devon: so I said to him, no way\n[8:00 PM] Gunnabot: Sounds like quite the story.\n"
                + "[8:01 PM] Sam: what happened next", db.conversation(GUILD, 99, NOW - 3600_000, NZ, "Gunnabot", 500));
        // only the latest lines when there are too many
        assertEquals("[8:01 PM] Sam: what happened next", db.conversation(GUILD, 99, NOW - 3600_000, NZ, "Gunnabot", 1));
    }
}
