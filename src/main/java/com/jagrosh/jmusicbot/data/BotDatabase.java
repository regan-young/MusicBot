/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * The bot's history, in one SQLite file: every song played, every line heard
 * in ?chat and what the bot made of it, the lines it got wrong, and the
 * changes people asked it to make to itself. The small JSON files (memories,
 * reminders, last seen) hold what the bot knows now; this holds what happened,
 * so it can be asked about: "play that again" after a restart, what this
 * server plays most, whether the router is butting in less than last month.
 *
 * Tables:
 * <ul>
 * <li>plays: a row per track started; how it ended and how far in.</li>
 * <li>voice_lines: a row per line heard; the transcript, timings, the router's
 *     score and the outcome ("none", "chat" or a command). bot_lines: what the
 *     bot said.</li>
 * <li>corrections: a line the bot answered that someone said wasn't for it.
 *     reference_transcripts: a better transcript of a line from another
 *     model, to measure speech recognition against.</li>
 * <li>code_requests: changes and questions sent to the coder sidecar.</li>
 * </ul>
 * and three views over them: play_counts, router_daily and stt_by_person.
 *
 * Writes are queued to one thread, so nothing that plays or listens waits on
 * the disk, and a failed write is logged and dropped: the history is worth
 * having, not worth a missed reply. Reads run where they're called.
 */
public final class BotDatabase implements AutoCloseable
{
    private static final Logger LOG = LoggerFactory.getLogger(BotDatabase.class);

    /** A track that was played. */
    public record Play(String title, String author, String uri, boolean radio, long durationMs, long requesterId,
            String requester, long startedAt) {}

    /** A track and how many times something happened to it. */
    public record Count(String title, String author, int times) {}

    // a track counts as cut short when it was stopped before this much of it had played
    private static final double CUT_SHORT = 0.5;

    private static final String[] SCHEMA = {
        "CREATE TABLE IF NOT EXISTS plays (id INTEGER PRIMARY KEY, guild_id INTEGER NOT NULL, started_at INTEGER NOT NULL, "
            + "ended_at INTEGER, title TEXT NOT NULL, author TEXT, uri TEXT, radio INTEGER NOT NULL DEFAULT 0, "
            + "duration_ms INTEGER, requester_id INTEGER, requester TEXT, ended TEXT, played_ms INTEGER)",
        "CREATE INDEX IF NOT EXISTS plays_guild_time ON plays (guild_id, started_at)",
        "CREATE TABLE IF NOT EXISTS voice_lines (id INTEGER PRIMARY KEY, guild_id INTEGER NOT NULL, session INTEGER, "
            + "heard_at INTEGER NOT NULL, user_id INTEGER, name TEXT, text TEXT, audio_ms INTEGER, stt_ms INTEGER, "
            + "stt_model TEXT, clip TEXT, echo INTEGER NOT NULL DEFAULT 0, named INTEGER, follow_up INTEGER, "
            + "router_ms INTEGER, confidence REAL, outcome TEXT, args TEXT, why TEXT)",
        "CREATE INDEX IF NOT EXISTS voice_lines_time ON voice_lines (guild_id, heard_at)",
        "CREATE INDEX IF NOT EXISTS voice_lines_user ON voice_lines (user_id)",
        "CREATE TABLE IF NOT EXISTS bot_lines (id INTEGER PRIMARY KEY AUTOINCREMENT, guild_id INTEGER NOT NULL, "
            + "session INTEGER, said_at INTEGER NOT NULL, text TEXT)",
        "CREATE TABLE IF NOT EXISTS corrections (id INTEGER PRIMARY KEY AUTOINCREMENT, guild_id INTEGER NOT NULL, "
            + "at INTEGER NOT NULL, line_id INTEGER, user_id INTEGER, speaker TEXT, line TEXT, routed TEXT, args TEXT, "
            + "corrected_by TEXT, correction TEXT, conversation TEXT)",
        "CREATE TABLE IF NOT EXISTS reference_transcripts (line_id INTEGER NOT NULL, model TEXT NOT NULL, text TEXT, "
            + "created_at INTEGER, PRIMARY KEY (line_id, model))",
        "CREATE TABLE IF NOT EXISTS code_requests (id TEXT PRIMARY KEY, kind TEXT NOT NULL, requested_at INTEGER NOT NULL, "
            + "requester_id INTEGER, requester TEXT, request TEXT, state TEXT, summary TEXT, error TEXT, commit_sha TEXT, "
            + "updated_at INTEGER)",
        // how often each track has been played and cut short, per server
        "CREATE VIEW IF NOT EXISTS play_counts AS SELECT guild_id, title, author, uri, COUNT(*) AS plays, "
            + "SUM(ended IN ('stopped', 'replaced') AND duration_ms > 0 AND played_ms < duration_ms * " + CUT_SHORT + ") AS cut_short, "
            + "MAX(started_at) AS last_played FROM plays WHERE radio = 0 GROUP BY guild_id, COALESCE(uri, title)",
        // the router, a day at a time (UTC): how much it heard, how much it took for the bot, how often it was told off
        "CREATE VIEW IF NOT EXISTS router_daily AS SELECT date(l.heard_at / 1000, 'unixepoch') AS day, l.guild_id, "
            + "COUNT(*) AS lines, SUM(l.outcome IS NOT NULL AND l.outcome != 'none') AS for_bot, "
            + "SUM(l.outcome NOT IN ('none', 'chat')) AS commands, ROUND(AVG(l.router_ms)) AS avg_router_ms, "
            + "ROUND(AVG(l.stt_ms)) AS avg_stt_ms, "
            + "(SELECT COUNT(*) FROM corrections c WHERE c.guild_id = l.guild_id "
            + "AND date(c.at / 1000, 'unixepoch') = date(l.heard_at / 1000, 'unixepoch')) AS corrections "
            + "FROM voice_lines l WHERE l.echo = 0 GROUP BY day, l.guild_id",
        // speech recognition per person: how much they've said, how long it takes, and how many lines have a reference to compare
        "CREATE VIEW IF NOT EXISTS stt_by_person AS SELECT l.user_id, MAX(l.name) AS name, COUNT(*) AS lines, "
            + "SUM(l.text = '') AS heard_nothing, ROUND(AVG(l.stt_ms)) AS avg_stt_ms, ROUND(SUM(l.audio_ms) / 60000.0, 1) AS minutes, "
            + "COUNT(r.line_id) AS with_reference FROM voice_lines l LEFT JOIN reference_transcripts r ON r.line_id = l.id "
            + "GROUP BY l.user_id",
    };

    private final Connection db;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "bot-database");
        t.setDaemon(true);
        return t;
    });
    // ids are made here, not by SQLite, so a row can be updated before its insert has been written
    private final AtomicLong ids = new AtomicLong(System.currentTimeMillis() * 1000);

    private BotDatabase(Connection db)
    {
        this.db = db;
    }

    /**
     * Opens the database, creating it if it isn't there.
     * @param path the file, or empty for no database
     * @return the database, or null if there's no path or it can't be opened (the bot runs without one)
     */
    public static BotDatabase open(String path)
    {
        if (path == null || path.isBlank())
            return null;
        try
        {
            SQLiteConfig config = new SQLiteConfig();
            // readers (a backup, someone looking with sqlite3) don't block the bot's writes, or the other way round
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
            config.setBusyTimeout(5000);
            SQLiteDataSource source = new SQLiteDataSource(config);
            source.setUrl("jdbc:sqlite:" + path);
            Connection db = source.getConnection();
            try (Statement s = db.createStatement())
            {
                for (String sql : SCHEMA)
                    s.execute(sql);
            }
            LOG.info("History is kept in {}", path);
            return new BotDatabase(db);
        }
        catch (SQLException | RuntimeException | LinkageError e)
        {
            // LinkageError: SQLite's native library didn't load
            LOG.warn("Could not open the database {}; carrying on without history", path, e);
            return null;
        }
    }

    /** An id for a new play or line. */
    public long nextId()
    {
        return ids.incrementAndGet();
    }

    // ------------------------------------------------------------------ plays

    public void playStarted(long id, long guildId, Play p)
    {
        write("INSERT INTO plays (id, guild_id, started_at, title, author, uri, radio, duration_ms, requester_id, requester) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, guildId, p.startedAt(), p.title(), p.author(), p.uri(),
                p.radio() ? 1 : 0, p.durationMs() > 0 && p.durationMs() < Long.MAX_VALUE / 2 ? p.durationMs() : null,
                p.requesterId() == 0 ? null : p.requesterId(), p.requester());
    }

    /**
     * @param reason how it ended: finished, stopped (skipped or stopped), replaced, load_failed, cleanup
     * @param playedMs how far into the track it was
     */
    public void playEnded(long id, String reason, long playedMs, long now)
    {
        write("UPDATE plays SET ended = ?, played_ms = ?, ended_at = ? WHERE id = ?", reason, playedMs, now, id);
    }

    /** The tracks most recently started in a guild, newest first, each track once. */
    public synchronized List<Play> recentPlays(long guildId, int limit)
    {
        return plays("SELECT title, author, uri, radio, duration_ms, requester_id, requester, MAX(started_at) AS at FROM plays "
                + "WHERE guild_id = ? GROUP BY COALESCE(uri, title) ORDER BY at DESC LIMIT ?", guildId, limit);
    }

    /**
     * The tracks played most since a time, most played first.
     * @param requesterId only what this person asked for, or 0 for everyone
     * @param atLeast leave out tracks played fewer times than this
     */
    public synchronized List<Count> mostPlayed(long guildId, long since, long requesterId, int atLeast, int limit)
    {
        return counts("SELECT title, author, COUNT(*) AS n FROM plays WHERE guild_id = ? AND radio = 0 AND started_at >= ? "
                + (requesterId == 0 ? "" : "AND requester_id = " + requesterId + " ")
                + "GROUP BY COALESCE(uri, title) HAVING n >= ? ORDER BY n DESC, MAX(started_at) DESC LIMIT ?",
                guildId, since, atLeast, limit);
    }

    /** The tracks cut short (skipped or stopped in their first half) at least twice since a time. */
    public synchronized List<Count> mostCutShort(long guildId, long since, int limit)
    {
        return counts("SELECT title, author, SUM(ended IN ('stopped', 'replaced') AND duration_ms > 0 AND played_ms < duration_ms * "
                + CUT_SHORT + ") AS n FROM plays WHERE guild_id = ? AND radio = 0 AND started_at >= ? "
                + "GROUP BY COALESCE(uri, title) HAVING n >= ? ORDER BY n DESC LIMIT ?", guildId, since, 2, limit);
    }

    /**
     * Everything the chat LLM needs to answer a question about what's been
     * played here: the favourites, who asks for what, and the latest plays
     * with their dates.
     * @param recent how many of the latest plays to list
     */
    public synchronized String musicDigest(long guildId, long now, ZoneId zone, int recent)
    {
        StringBuilder sb = new StringBuilder();
        try
        {
            long total = number("SELECT COUNT(*) FROM plays WHERE guild_id = ?", guildId);
            long first = number("SELECT MIN(started_at) FROM plays WHERE guild_id = ?", guildId);
            if (total == 0)
                return "Nothing has been played since the history began.";
            DateTimeFormatter day = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH).withZone(zone);
            DateTimeFormatter time = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH).withZone(zone);
            sb.append("History of music played in this server: ").append(total).append(" plays since ")
              .append(day.format(Instant.ofEpochMilli(first))).append(". Today is ").append(day.format(Instant.ofEpochMilli(now)))
              .append(". Nothing earlier was recorded.\n\nMost played of all (times):\n");
            for (Count c : mostPlayed(guildId, 0, 0, 1, 25))
                sb.append("- ").append(label(c.title(), c.author())).append(" (").append(c.times()).append(")\n");
            List<Count> cut = mostCutShort(guildId, 0, 10);
            if (!cut.isEmpty())
            {
                sb.append("\nCut short most often (skipped or stopped in the first half; times):\n");
                for (Count c : cut)
                    sb.append("- ").append(label(c.title(), c.author())).append(" (").append(c.times()).append(")\n");
            }
            sb.append("\nWho asks for the most, and their most asked for:\n");
            try (PreparedStatement s = prepare("SELECT requester_id, MAX(requester) AS who, COUNT(*) AS n FROM plays "
                    + "WHERE guild_id = ? AND radio = 0 AND requester_id IS NOT NULL GROUP BY requester_id ORDER BY n DESC LIMIT 12",
                    guildId); ResultSet r = s.executeQuery())
            {
                while (r.next())
                {
                    sb.append("- ").append(r.getString("who")).append(" (").append(r.getInt("n")).append(" plays): ");
                    List<String> favourites = new ArrayList<>();
                    for (Count c : mostPlayed(guildId, 0, r.getLong("requester_id"), 1, 5))
                        favourites.add(label(c.title(), c.author()) + (c.times() > 1 ? " x" + c.times() : ""));
                    sb.append(String.join("; ", favourites)).append('\n');
                }
            }
            sb.append("\nThe latest plays, newest first:\n");
            try (PreparedStatement s = prepare("SELECT title, author, radio, requester, started_at, ended, played_ms, duration_ms "
                    + "FROM plays WHERE guild_id = ? ORDER BY started_at DESC LIMIT ?", guildId, recent);
                    ResultSet r = s.executeQuery())
            {
                while (r.next())
                {
                    long duration = r.getLong("duration_ms");
                    boolean cutShort = duration > 0 && r.getLong("played_ms") < duration * CUT_SHORT
                            && ("stopped".equals(r.getString("ended")) || "replaced".equals(r.getString("ended")));
                    sb.append("- ").append(time.format(Instant.ofEpochMilli(r.getLong("started_at")))).append(": ")
                      .append(r.getInt("radio") == 1 ? "radio station " : "").append(label(r.getString("title"), r.getString("author")))
                      .append(r.getString("requester") == null ? "" : ", asked for by " + r.getString("requester"))
                      .append(cutShort ? ", cut short" : "").append('\n');
                }
            }
        }
        catch (SQLException e)
        {
            LOG.warn("Could not read the play history", e);
        }
        return sb.toString();
    }

    /** "Dreams by Fleetwood Mac", or just the title when there's no artist. */
    public static String label(String title, String author)
    {
        return author == null || author.isBlank() ? title : title + " by " + author;
    }

    // ------------------------------------------------------------------ voice lines

    /** A line someone said in ?chat, as the speech model heard it. */
    public void lineHeard(long id, long guildId, long session, long heardAt, long userId, String name, String text, long audioMs,
            long sttMs, String sttModel, String clip, boolean echo)
    {
        write("INSERT INTO voice_lines (id, guild_id, session, heard_at, user_id, name, text, audio_ms, stt_ms, stt_model, clip, echo) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, guildId, session, heardAt, userId, name, text, audioMs, sttMs,
                sttModel, clip, echo ? 1 : 0);
    }

    /**
     * What the bot made of a line.
     * @param outcome "none" (not for the bot), "chat", or the command that ran
     * @param confidence the router's score, or null if it gave none
     * @param routerMs how long the router took, or null if it wasn't asked
     */
    public void lineRouted(long id, String outcome, String args, String why, boolean named, boolean followUp, Double confidence,
            Long routerMs)
    {
        write("UPDATE voice_lines SET outcome = ?, args = ?, why = ?, named = ?, follow_up = ?, confidence = ?, router_ms = ? "
                + "WHERE id = ?", outcome, args == null || args.isEmpty() ? null : args, why == null || why.isEmpty() ? null : why,
                named ? 1 : 0, followUp ? 1 : 0, confidence, routerMs, id);
    }

    /** A sentence the bot spoke in ?chat. */
    public void botLine(long guildId, long session, long now, String text)
    {
        write("INSERT INTO bot_lines (guild_id, session, said_at, text) VALUES (?, ?, ?, ?)", guildId, session, now, text);
    }

    /**
     * A line the bot acted on that someone then said wasn't for it.
     * @param lineId the line's id in voice_lines, or 0 if it isn't known
     * @param conversation what the router saw, as a JSON array of lines
     */
    public void correction(long guildId, long now, long lineId, long userId, String speaker, String line, String routed,
            String args, String correctedBy, String correction, String conversation)
    {
        write("INSERT INTO corrections (guild_id, at, line_id, user_id, speaker, line, routed, args, corrected_by, correction, "
                + "conversation) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", guildId, now, lineId == 0 ? null : lineId,
                userId == 0 ? null : userId, speaker, line, routed, args, correctedBy, correction, conversation);
    }

    /**
     * A session's conversation since a time, oldest first, as "[8:31 PM] Name: text" lines, for the chat LLM to
     * recap. The bot's own voice heard back and empty transcripts are left out.
     * @param limit the most lines to give, the latest ones
     */
    public synchronized String conversation(long guildId, long session, long since, ZoneId zone, String botName, int limit)
    {
        DateTimeFormatter time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(zone);
        List<String> lines = new ArrayList<>();
        try (PreparedStatement s = prepare("SELECT at, name, text FROM (SELECT heard_at AS at, name, text FROM voice_lines "
                + "WHERE guild_id = ? AND session = ? AND heard_at >= ? AND echo = 0 AND text != '' "
                + "UNION ALL SELECT said_at, ?, text FROM bot_lines WHERE guild_id = ? AND session = ? AND said_at >= ? "
                + "ORDER BY at DESC LIMIT ?) ORDER BY at", guildId, session, since, botName, guildId, session, since, limit);
                ResultSet r = s.executeQuery())
        {
            while (r.next())
                lines.add("[" + time.format(Instant.ofEpochMilli(r.getLong("at"))) + "] " + r.getString("name") + ": " + r.getString("text"));
        }
        catch (SQLException e)
        {
            LOG.warn("Could not read the conversation", e);
        }
        return String.join("\n", lines);
    }

    // ------------------------------------------------------------------ changes to the bot

    /** A change or a question sent to the coder sidecar. @param kind "change" or "ask" */
    public void codeRequested(String id, String kind, long now, long requesterId, String requester, String request)
    {
        write("INSERT OR IGNORE INTO code_requests (id, kind, requested_at, requester_id, requester, request, state, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'queued', ?)", id, kind, now, requesterId == 0 ? null : requesterId, requester, request, now);
    }

    /** How a change or question is getting on; a null field keeps what's there. */
    public void codeUpdated(String id, long now, String state, String summary, String error, String commit)
    {
        write("UPDATE code_requests SET state = ?, summary = COALESCE(?, summary), error = COALESCE(?, error), "
                + "commit_sha = COALESCE(?, commit_sha), updated_at = ? WHERE id = ?", state, blankToNull(summary),
                blankToNull(error), blankToNull(commit), now, id);
    }

    // ------------------------------------------------------------------ housekeeping

    /**
     * Forgets a person: what they said goes, and their name comes off what
     * they played and asked for (the songs still count for the server).
     */
    public void forget(long userId)
    {
        write("DELETE FROM reference_transcripts WHERE line_id IN (SELECT id FROM voice_lines WHERE user_id = ?)", userId);
        write("DELETE FROM voice_lines WHERE user_id = ?", userId);
        write("DELETE FROM corrections WHERE user_id = ?", userId);
        write("UPDATE plays SET requester_id = NULL, requester = NULL WHERE requester_id = ?", userId);
        write("UPDATE code_requests SET requester_id = NULL, requester = NULL WHERE requester_id = ?", userId);
    }

    /** Drops what was said (by people and by the bot) before a time. Plays and code requests are kept. */
    public void forgetSpeechBefore(long time)
    {
        write("DELETE FROM reference_transcripts WHERE line_id IN (SELECT id FROM voice_lines WHERE heard_at < ?)", time);
        write("DELETE FROM voice_lines WHERE heard_at < ?", time);
        write("DELETE FROM bot_lines WHERE said_at < ?", time);
        write("DELETE FROM corrections WHERE at < ?", time);
    }

    /** Waits for the writes queued so far, for tests and for shutting down. */
    public void flush()
    {
        try
        {
            writer.submit(() -> {}).get(10, TimeUnit.SECONDS);
        }
        catch (Exception e)
        {
            LOG.warn("The database is slow to catch up", e);
        }
    }

    @Override
    public void close()
    {
        flush();
        writer.shutdown();
        synchronized (this)
        {
            try
            {
                db.close();
            }
            catch (SQLException e)
            {
                LOG.warn("Could not close the database", e);
            }
        }
    }

    /** A number from a one-value query, for tests and reports. */
    public synchronized long number(String sql, Object... params) throws SQLException
    {
        try (PreparedStatement s = prepare(sql, params); ResultSet r = s.executeQuery())
        {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    private void write(String sql, Object... params)
    {
        try
        {
            writer.execute(() ->
            {
                synchronized (this)
                {
                    try (PreparedStatement s = prepare(sql, params))
                    {
                        s.executeUpdate();
                    }
                    catch (SQLException e)
                    {
                        LOG.warn("Could not write to the database: {}", sql, e);
                    }
                }
            });
        }
        catch (java.util.concurrent.RejectedExecutionException e)
        {
            // closed: the bot is shutting down
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException
    {
        PreparedStatement s = db.prepareStatement(sql);
        for (int i = 0; i < params.length; i++)
            s.setObject(i + 1, params[i]);
        return s;
    }

    private List<Play> plays(String sql, Object... params)
    {
        List<Play> out = new ArrayList<>();
        try (PreparedStatement s = prepare(sql, params); ResultSet r = s.executeQuery())
        {
            while (r.next())
                out.add(new Play(r.getString("title"), r.getString("author"), r.getString("uri"), r.getInt("radio") == 1,
                        r.getLong("duration_ms"), r.getLong("requester_id"), r.getString("requester"), r.getLong("at")));
        }
        catch (SQLException e)
        {
            LOG.warn("Could not read the play history", e);
        }
        return out;
    }

    private List<Count> counts(String sql, Object... params)
    {
        List<Count> out = new ArrayList<>();
        try (PreparedStatement s = prepare(sql, params); ResultSet r = s.executeQuery())
        {
            while (r.next())
                out.add(new Count(r.getString("title"), r.getString("author"), r.getInt("n")));
        }
        catch (SQLException e)
        {
            LOG.warn("Could not read the play history", e);
        }
        return out;
    }

    private static String blankToNull(String s)
    {
        return s == null || s.isBlank() ? null : s;
    }
}
