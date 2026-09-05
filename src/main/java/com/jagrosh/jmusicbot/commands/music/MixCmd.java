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
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.dv8tion.jda.api.entities.Message;

/**
 * Queues songs similar to a seed track using YouTube's own "mix" radio
 * playlists (watch?v=ID&list=RDID), resolved through yt-dlp. No API key or
 * external recommendation service is involved.
 *
 * @author Regan Young
 */
public class MixCmd extends MusicCommand {
    // A YouTube video id, either bare or inside a watch/youtu.be/shorts URL.
    private final static Pattern VIDEO_ID = Pattern.compile(
            "(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})");
    private final static Pattern BARE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private final String loadingEmoji;

    public MixCmd(Bot bot) {
        super(bot);
        this.loadingEmoji = bot.getConfig().getLoading();
        this.name = "mix";
        this.arguments = "<title|URL>";
        this.help = "queues songs similar to the given song";
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
                event.replyError("Give me a song to build a mix around, or play something first.\n"
                        + "Usage: `" + event.getClient().getPrefix() + name + " " + arguments + "`");
                return;
            }
            String id = extractVideoId(playing);
            if (id == null) {
                event.replyError("**" + playing.getInfo().title + "** isn't a YouTube track, so I can't build a mix from it.");
                return;
            }
            event.reply(loadingEmoji + " Building a mix around **" + playing.getInfo().title + "**...",
                    m -> loadMix(event, m, id, playing.getInfo().title));
            return;
        }

        // An explicit id or URL can skip the search entirely.
        String direct = extractVideoId(args);
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
            String id = extractVideoId(track);
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
            boolean wasRadio = RadioCmd.isRadioStream(handler);

            int wanted = bot.getConfig().getMixSongs();
            int added = 0, skippedLong = 0;
            StringBuilder list = new StringBuilder();

            for (AudioTrack track : playlist.getTracks()) {
                if (added >= wanted)
                    break;
                // The mix always leads with the seed itself; the user asked for
                // songs *like* it, so leave it out.
                if (seedId.equals(extractVideoId(track)))
                    continue;
                if (bot.getConfig().isTooLong(track)) {
                    skippedLong++;
                    continue;
                }
                handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                added++;
                list.append("\n`").append(added).append(".` ").append(track.getInfo().title);
            }

            // Stop the radio only after queueing, so the track-end handler does
            // not see an empty queue and disconnect.
            if (wasRadio && added > 0)
                handler.getPlayer().stopTrack();

            if (added == 0) {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                        + " That mix had nothing I could queue"
                        + (skippedLong > 0 ? " (everything was longer than `" + bot.getConfig().getMaxTime() + "`)" : "")
                        + ".")).queue();
                return;
            }

            m.editMessage(FormatUtil.filter(event.getClient().getSuccess() + " Queued **" + added + "** songs like "
                    + (seedTitle == null ? "that" : "**" + seedTitle + "**") + ":" + list
                    + (skippedLong > 0 ? "\n" + event.getClient().getWarning() + " Skipped " + skippedLong
                            + " track(s) longer than `" + bot.getConfig().getMaxTime() + "`." : "")))
                    .queue();
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

    private static String extractVideoId(AudioTrack track) {
        String fromIdentifier = extractVideoId(track.getInfo().identifier);
        return fromIdentifier != null ? fromIdentifier : extractVideoId(track.getInfo().uri);
    }

    private static String extractVideoId(String s) {
        if (s == null || s.isEmpty())
            return null;
        if (BARE_ID.matcher(s).matches())
            return s;
        Matcher matcher = VIDEO_ID.matcher(s);
        return matcher.find() ? matcher.group(1) : null;
    }
}
