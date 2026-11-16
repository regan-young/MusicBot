/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
 *
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
package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.LastFm;
import com.jagrosh.jmusicbot.audio.MixPlanner;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.audio.TrackArt;
import com.jagrosh.jmusicbot.audio.Variety;
import com.jagrosh.jmusicbot.data.BotDatabase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;


import net.dv8tion.jda.api.entities.Message;

/**
 * Queues songs similar to a seed track using YouTube's own "mix" radio
 * playlists (watch?v=ID&list=RDID), resolved through yt-dlp. No API key or
 * external recommendation service is involved.
 *
 * @author Regan Young
 */
public class MixCmd extends MusicCommand {
    private static final Logger LOG = LoggerFactory.getLogger(MixCmd.class);
    private final String loadingEmoji;
    private final LastFm lastFm;

    public MixCmd(Bot bot) {
        super(bot);
        this.loadingEmoji = bot.getConfig().getLoading();
        this.lastFm = new LastFm(bot.getConfig().getLastFmKey());
        this.name = "mix";
        this.arguments = "<title|URL>";
        this.help = "queues a varied mix of songs like the given song (YouTube + Last.fm)";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
    }

    @Override
    public void doCommand(CommandEvent event) {
        String args = event.getArgs().trim();
        if (args.startsWith("<") && args.endsWith(">"))
            args = args.substring(1, args.length() - 1);

        // With no argument, seed from whatever is playing.
        if (args.isEmpty()) {
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            AudioTrack playing = handler == null ? null : handler.getPlayer().getPlayingTrack();
            if (playing == null) {
                // nothing on: build it around the last song played here
                AudioHandler.Played last = handler == null ? null : handler.getHistory().stream()
                        .filter(p -> !p.radio() && p.uri() != null && TrackArt.youtubeId(p.uri()) != null)
                        .findFirst().orElse(null);
                if (last == null) {
                    event.replyError("Give me a song to build a mix around, or play something first.\n"
                            + "Usage: `" + event.getClient().getPrefix() + name + " " + arguments + "`");
                    return;
                }
                event.reply(loadingEmoji + " Building a mix around **" + last.title() + "**...",
                        m -> loadMix(event, m, TrackArt.youtubeId(last.uri()), last.title()));
                return;
            }
            String id = TrackArt.youtubeId(playing);
            if (id == null) {
                event.replyError("**" + playing.getInfo().title + "** isn't a YouTube track, so I can't build a mix from it.");
                return;
            }
            event.reply(loadingEmoji + " Building a mix around **" + playing.getInfo().title + "**...",
                    m -> loadMix(event, m, id, playing.getInfo().title));
            return;
        }

        // An explicit id or URL can skip the search entirely.
        String direct = TrackArt.youtubeId(args);
        if (direct != null) {
            event.reply(loadingEmoji + " Building a mix... `[" + args + "]`",
                    m -> loadMix(event, m, direct, null));
            return;
        }

        final String query = args;
        event.reply(loadingEmoji + " Finding **" + query + "**...",
                m -> bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + query,
                        new SeedHandler(m, event, query)));
    }

    /** Resolves the seed song, then hands off to the mix loader. */
    private class SeedHandler implements AudioLoadResultHandler {
        private final Message m;
        private final CommandEvent event;
        private final String query;

        private SeedHandler(Message m, CommandEvent event, String query) {
            this.m = m;
            this.event = event;
            this.query = query;
        }

        private void seed(AudioTrack track) {
            String id = TrackArt.youtubeId(track);
            if (id == null) {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                        + " Couldn't work out a YouTube id for **" + track.getInfo().title + "**.")).queue();
                return;
            }
            loadMix(event, m, id, track.getInfo().title);
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            seed(track);
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            if (playlist.getTracks().isEmpty()) {
                noMatches();
                return;
            }
            seed(playlist.getSelectedTrack() == null ? playlist.getTracks().get(0) : playlist.getSelectedTrack());
        }

        @Override
        public void noMatches() {
            m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                    + " No results found for `" + query + "`.")).queue();
        }

        @Override
        public void loadFailed(FriendlyException throwable) {
            m.editMessage(FormatUtil.filter(event.getClient().getError()
                    + " Couldn't find that song: " + throwable.getMessage())).queue();
        }
    }

    /** Loads YouTube's mix for the seed id and queues the results. */
    private void loadMix(CommandEvent event, Message m, String seedId, String seedTitle) {
        String mixUrl = "https://www.youtube.com/watch?v=" + seedId + "&list=RD" + seedId;
        bot.getPlayerManager().loadItemOrdered(event.getGuild(), mixUrl,
                new MixHandler(m, event, seedId, seedTitle));
    }

    // what a mix shouldn't bring back: the last week's plays, and what's playing or queued
    private static final long RECENT_MS = 7 * 86_400_000L;
    private static final int RECENT_PLAYS = 200;

    private void recent(long guildId, AudioHandler handler, Set<String> ids, Set<String> keys) {
        long since = System.currentTimeMillis() - RECENT_MS;
        BotDatabase db = bot.getDatabase();
        if (db != null) {
            for (BotDatabase.Play p : db.recentPlays(guildId, RECENT_PLAYS)) {
                if (p.startedAt() < since)
                    break;
                add(ids, keys, p.uri(), p.title(), p.author());
            }
        }
        for (AudioHandler.Played p : handler.getHistory())
            if (p.time() >= since)
                add(ids, keys, p.uri(), p.title(), p.author());
        AudioTrack playing = handler.getPlayer().getPlayingTrack();
        if (playing != null)
            add(ids, keys, playing.getInfo().uri, playing.getInfo().title, playing.getInfo().author);
        for (QueuedTrack q : handler.getQueue().getList())
            add(ids, keys, q.getTrack().getInfo().uri, q.getTrack().getInfo().title, q.getTrack().getInfo().author);
    }

    private static void add(Set<String> ids, Set<String> keys, String uri, String title, String author) {
        String id = uri == null ? null : TrackArt.youtubeId(uri);
        if (id != null)
            ids.add(id);
        keys.add(Variety.key(title, author));
    }

    /**
     * The seed as Last.fm knows it: "Artist - Title" uploads split at the
     * dash; a bare title goes with the channel as its artist. Null if it
     * can't be told.
     */
    private LastFm.Song seedSong(String title, String author) {
        if (!lastFm.isEnabled() || title == null || title.isBlank())
            return null;
        int dash = title.indexOf(" - ");
        if (dash > 0) {
            String artist = title.substring(0, dash).replaceAll("(?i)\\s*(,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b)\\s.*$", "").trim();
            String song = Variety.song(title);
            if (!artist.isEmpty() && !song.isEmpty())
                return new LastFm.Song(artist, song);
        }
        // a bare title: the channel is usually the artist ("Royal Blood - Topic", "RoyalBloodVEVO"). A title
        // search guesses wrong too often (Albert Ayler's "Summertime" came back as The Sundays); if the
        // channel isn't an artist Last.fm knows, it returns nothing and the mix is YouTube's alone
        String song = Variety.song(title), artist = Variety.artist("", author);
        return song.isEmpty() || artist.isEmpty() ? null : new LastFm.Song(artist, song);
    }

    private class MixHandler implements AudioLoadResultHandler {
        private final Message m;
        private final CommandEvent event;
        private final String seedId;
        private final String seedTitle;

        private MixHandler(Message m, CommandEvent event, String seedId, String seedTitle) {
            this.m = m;
            this.event = event;
            this.seedId = seedId;
            this.seedTitle = seedTitle;
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            int wanted = bot.getConfig().getMixSongs();
            int skippedLong = 0;
            AudioTrack seed = null;

            // The mix always leads with the seed itself; the user asked for
            // songs *like* it, so leave it out, and anything too long.
            List<MixPlanner.Item<AudioTrack>> youtube = new ArrayList<>();
            for (AudioTrack track : playlist.getTracks()) {
                String id = TrackArt.youtubeId(track);
                if (seedId.equals(id)) {
                    seed = track;
                    continue;
                }
                if (bot.getConfig().isTooLong(track)) {
                    skippedLong++;
                    continue;
                }
                youtube.add(new MixPlanner.Item<>(track, id, track.getInfo().title, track.getInfo().author, MixPlanner.YOUTUBE));
            }
            // and nothing played this past week or already queued
            Set<String> recentIds = new HashSet<>(), recentKeys = new HashSet<>();
            recent(event.getGuild().getIdLong(), handler, recentIds, recentKeys);
            String title = seed != null ? seed.getInfo().title : seedTitle;
            String author = seed != null ? seed.getInfo().author : null;
            String seedArtist = Variety.artist(title, author);
            final int longOnes = skippedLong;
            // Last.fm is a few web calls: off the player's threads
            CompletableFuture.supplyAsync(() -> MixPlanner.lastFmCandidates(lastFm, seedSong(title, author),
                    title != null && title.contains(" - "), new Random()))
                    .exceptionally(e -> {
                        LOG.warn("Last.fm part of the mix failed", e);
                        return List.of();
                    })
                    .thenAccept(songs -> {
                        List<MixPlanner.Item<AudioTrack>> fm = new ArrayList<>();
                        songs.forEach(x -> fm.add(MixPlanner.lastFm(x)));
                        List<MixPlanner.Item<AudioTrack>> plan = MixPlanner.merge(youtube, fm, recentIds, recentKeys,
                                seedArtist, wanted, new Random());
                        LOG.info("Mix around {}: {} candidates from YouTube, {} from Last.fm; picked {}", title,
                                youtube.size(), fm.size(), plan.stream().map(i -> i.title() + " (" + i.source() + ")").toList());
                        queue(plan, 0, new Queued(recentIds, longOnes));
                    });
        }

        /** What has been queued so far, for the message at the end. */
        private class Queued {
            final Set<String> avoidIds;
            final int skippedLong;
            final StringBuilder list = new StringBuilder();
            int added;
            AudioTrack first;

            Queued(Set<String> avoidIds, int skippedLong) {
                this.avoidIds = avoidIds;
                this.skippedLong = skippedLong;
            }
        }

        /** Queues the plan in order, one at a time; a Last.fm pick is searched on YouTube first. */
        private void queue(List<MixPlanner.Item<AudioTrack>> plan, int i, Queued q) {
            if (i >= plan.size()) {
                finish(q);
                return;
            }
            MixPlanner.Item<AudioTrack> item = plan.get(i);
            if (item.track() != null) {
                add(item.track(), item.source(), q);
                queue(plan, i + 1, q);
                return;
            }
            bot.getPlayerManager().loadItemOrdered(event.getGuild(), "ytsearch:" + item.title(), new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    consider(List.of(track));
                }

                @Override
                public void playlistLoaded(AudioPlaylist results) {
                    consider(results.getTracks());
                }

                private void consider(List<AudioTrack> results) {
                    for (AudioTrack t : results.subList(0, Math.min(3, results.size()))) {
                        String id = TrackArt.youtubeId(t);
                        if (bot.getConfig().isTooLong(t) || (id != null && q.avoidIds.contains(id)))
                            continue;
                        add(t, item.source(), q);
                        break;
                    }
                    queue(plan, i + 1, q);
                }

                @Override
                public void noMatches() {
                    queue(plan, i + 1, q);
                }

                @Override
                public void loadFailed(FriendlyException e) {
                    queue(plan, i + 1, q);
                }
            });
        }

        private void add(AudioTrack track, String source, Queued q) {
            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
            handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
            String id = TrackArt.youtubeId(track);
            if (id != null)
                q.avoidIds.add(id);
            if (q.first == null)
                q.first = track;
            q.added++;
            q.list.append("\n`").append(q.added).append(".` ").append(track.getInfo().title)
                    .append(MixPlanner.LASTFM.equals(source) ? " *(Last.fm)*" : "");
        }

        private void finish(Queued q) {
            if (q.added == 0) {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                        + " That mix had nothing I could queue"
                        + (q.skippedLong > 0 ? " (everything was longer than `" + bot.getConfig().getMaxTime() + "`)" : "")
                        + ".")).queue();
                return;
            }
            m.editMessage(bot.getPlayerControls().edit(event.getGuild(), FormatUtil.filter(event.getClient().getSuccess() + " Queued **" + q.added + "** songs like "
                    + (seedTitle == null ? "that" : "**" + seedTitle + "**") + ":" + q.list
                    + (q.skippedLong > 0 ? "\n" + event.getClient().getWarning() + " Skipped " + q.skippedLong
                            + " track(s) longer than `" + bot.getConfig().getMaxTime() + "`." : "")), q.first))
                    .queue(bot.getPlayerControls()::register);
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            // A mix URL resolving to one track means YouTube had no radio for it.
            m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                    + " YouTube doesn't have a mix for that song.")).queue();
        }

        @Override
        public void noMatches() {
            m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                    + " YouTube doesn't have a mix for that song.")).queue();
        }

        @Override
        public void loadFailed(FriendlyException throwable) {
            m.editMessage(FormatUtil.filter(event.getClient().getError()
                    + " Couldn't load that mix: " + throwable.getMessage())).queue();
        }
    }
}
