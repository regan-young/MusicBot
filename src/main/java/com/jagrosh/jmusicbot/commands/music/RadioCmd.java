/*
 * Copyright 2024
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

import com.jagrosh.jmusicbot.audio.RadioBrowser;
import net.dv8tion.jda.api.entities.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RadioCmd extends MusicCommand {
    private final Map<String, String> stations;
    private final Map<String, String> stationDisplayNames;
    private final Map<Long, String> currentStationByGuild;
    private final List<String> sortedStationKeys;
    private final RadioBrowser browser = new RadioBrowser();
    // Prefix for /radio autocomplete values naming a radio-browser station
    public static final String WORLD_PREFIX = "rb:";

    public RadioCmd(Bot bot) {
        super(bot);
        this.name = "radio";
        this.help = "plays a radio station: NZ favourites or any station worldwide";
        this.arguments = "<station name|list|skip>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;

        this.stations = new HashMap<>();
        this.stationDisplayNames = new HashMap<>();
        this.currentStationByGuild = new ConcurrentHashMap<>();

        // RNZ
        addStation("rnz", "RNZ", "http://radionz-ice.streamguys.com/national.mp3");

        // NZME Stations (StreamTheWorld)
        addStation("newstalkzb", "Newstalk ZB",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_41AAC.aac");
        addStation("zm", "ZM", "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_03AAC.aac");
        addStation("flava", "Flava", "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_08AAC_SC");
        addStation("coast", "Coast", "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_07AAC_SC");
        addStation("gold", "Gold", "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_05_SC");
        addStation("thehits", "The Hits",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_12AAC_SC");
        addStation("hauraki", "Radio Hauraki",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_04AAC_SC");

        // MediaWorks Stations
        addStation("maifm", "Mai FM", "https://mediaworks.streamguys1.com/mai_net_icy");
        addStation("georgefm", "George FM", "https://mediaworks.streamguys1.com/george_net_icy");
        addStation("theedge", "The Edge", "https://mediaworks.streamguys1.com/edge_net_icy");
        addStation("therock", "The Rock", "https://mediaworks.streamguys1.com/rock_net_icy");
        addStation("morefm", "More FM", "https://mediaworks.streamguys1.com/more_net_icy");
        addStation("thebreeze", "The Breeze", "https://mediaworks.streamguys1.com/breeze_net_icy");
        addStation("thesound", "The Sound", "https://mediaworks.streamguys1.com/sound_net_icy");
        addStation("magic", "Magic", "https://mediaworks.streamguys1.com/magic_net_icy");

        // International
        // No ICY metadata in Radio France streams; song titles come from livemeta (7 = FIP)
        addStation("fip", "FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3",
                "https://api.radiofrance.fr/livemeta/pull/7");

        // Build sorted list for skip functionality
        this.sortedStationKeys = stations.keySet().stream().sorted().collect(Collectors.toList());
    }


    private void addStation(String key, String displayName, String url) {
        addStation(key, displayName, url, null);
    }

    private void addStation(String key, String displayName, String url, String livemetaUrl) {
        stations.put(key, url);
        stationDisplayNames.put(key, displayName);
        bot.getRadioMetadata().register(url, displayName, livemetaUrl, null);
    }

    /**
     * /radio autocomplete: curated stations matching what's typed, then (once
     * a few letters are in) worldwide matches. Returns value -> label; world
     * values are {@link #WORLD_PREFIX} + the radio-browser id.
     */
    public Map<String, String> autocomplete(String typed) {
        String query = typed.trim().toLowerCase(Locale.ROOT);
        Map<String, String> choices = new LinkedHashMap<>();
        if ("list".startsWith(query))
            choices.put("list", "📋 Show the station list");
        for (String key : sortedStationKeys) {
            String display = stationDisplayNames.get(key);
            if (key.contains(squash(query)) || display.toLowerCase(Locale.ROOT).contains(query))
                choices.put(key, "🇳🇿 " + display);
        }
        if (query.length() >= 3) {
            // Discord drops autocomplete answers after 3 seconds
            try {
                List<RadioBrowser.Station> world = CompletableFuture
                        .supplyAsync(() -> browser.search(query, 20))
                        .get(2200, TimeUnit.MILLISECONDS);
                for (RadioBrowser.Station s : world)
                    choices.putIfAbsent(WORLD_PREFIX + s.uuid, "🌍 " + s.label());
            } catch (Exception ignored) {
                // slow or unreachable: offer the curated matches only
            }
        }
        return choices;
    }

    /** Curated key for typed text: "the edge" -> "theedge", or by display name. */
    private String curatedKey(String args) {
        String squashed = squash(args);
        if (stations.containsKey(squashed))
            return squashed;
        for (Map.Entry<String, String> e : stationDisplayNames.entrySet())
            if (e.getValue().equalsIgnoreCase(args))
                return e.getKey();
        return null;
    }

    private static String squash(String s) {
        return s.replaceAll("[^a-z0-9]", "");
    }

    @Override
    public void doCommand(CommandEvent event) {
        String raw = event.getArgs().trim();
        String args = raw.toLowerCase(Locale.ROOT);

        if (args.isEmpty() || args.equals("list")) {
            showList(event);
            return;
        }

        if (args.equals("skip") || args.equals("next")) {
            skip(event);
            return;
        }

        String key = curatedKey(args);
        if (key != null) {
            play(event, null, stations.get(key), stationDisplayNames.get(key), key, null);
            return;
        }

        // An exact pick from /radio autocomplete
        if (args.startsWith(WORLD_PREFIX)) {
            String uuid = raw.substring(WORLD_PREFIX.length());
            event.reply(event.getClient().getSuccess() + " Tuning in...", m -> CompletableFuture
                    .supplyAsync(() -> browser.byUuid(uuid))
                    .thenAccept(s -> {
                        if (s == null)
                            m.editMessage(event.getClient().getWarning() + " That station has gone from the directory.").queue();
                        else
                            playWorld(event, m, s, null);
                    }));
            return;
        }

        event.reply("🔎 Searching radio stations worldwide for **" + FormatUtil.filter(raw) + "**...",
                m -> CompletableFuture
                        .supplyAsync(() -> browser.search(raw, 5))
                        .thenAccept(results -> {
                            if (results.isEmpty()) {
                                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                                        + " No radio station found for **" + raw + "**. Type `"
                                        + event.getClient().getPrefix() + name + " list` for the NZ favourites.")).queue();
                                return;
                            }
                            playWorld(event, m, results.get(0), results.subList(1, results.size()));
                        }));
    }

    private void showList(CommandEvent event) {
        String prefix = event.getClient().getPrefix();
        StringBuilder builder = new StringBuilder();
        builder.append("__**📻 Radio**__\n");
        builder.append("`").append(prefix).append(name).append(" <station>` - play one of the stations below\n");
        builder.append("`").append(prefix).append(name).append(" <any name>` - search ~50,000 stations worldwide (e.g. `")
                .append(prefix).append(name).append(" centreforce`)\n");
        builder.append("`").append(prefix).append(name).append(" skip` - next station in this list\n");
        builder.append("\n__**NZ favourites & more:**__\n");
        for (String stationKey : sortedStationKeys)
            builder.append("`").append(stationKey).append("` - ").append(stationDisplayNames.get(stationKey)).append("\n");
        event.reply(builder.toString());
    }

    private void skip(CommandEvent event) {
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler == null || !bot.getRadioMetadata().isRadio(handler.getPlayer().getPlayingTrack())) {
            event.replyError("No radio station is currently playing. Use `" + event.getClient().getPrefix() + name
                    + " <station name>` to start one.");
            return;
        }
        // From a worldwide station (not in the list), start at the top of the list
        String currentKey = currentStationByGuild.get(event.getGuild().getIdLong());
        int next = currentKey == null ? 0 : (sortedStationKeys.indexOf(currentKey) + 1) % sortedStationKeys.size();
        String nextKey = sortedStationKeys.get(next);
        play(event, null, stations.get(nextKey), stationDisplayNames.get(nextKey), nextKey, null);
    }

    /** Plays a radio-browser station, mentioning the other matches in case it's the wrong one. */
    private void playWorld(CommandEvent event, Message m, RadioBrowser.Station station, List<RadioBrowser.Station> others) {
        // Registering makes it a radio station to the rest of the bot: song
        // titles, status, and being replaced by anything queued
        bot.getRadioMetadata().register(station.url, station.label(), null, station.favicon);
        String extra = null;
        if (others != null && !others.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (RadioBrowser.Station o : others)
                names.add("`" + o.label() + "`");
            extra = "Not it? Also found " + String.join(", ", names) + ". Use the exact name, or pick from `/radio`.";
        }
        play(event, m, station.url, station.label(), null, extra);
    }

    /**
     * Loads a station and reports it. Replies "Loading..." first unless a
     * message to edit is given; curated stations pass their key for skip.
     */
    private void play(CommandEvent event, Message m, String url, String displayName, String key, String extra) {
        if (m == null) {
            event.reply(event.getClient().getSuccess() + " Loading **" + FormatUtil.filter(displayName) + "**...",
                    msg -> play(event, msg, url, displayName, key, extra));
            return;
        }
        bot.getPlayerManager().loadItemOrdered(event.getGuild(), url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
                // addTrack replaces a station that is already playing
                handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                if (key == null)
                    currentStationByGuild.remove(event.getGuild().getIdLong());
                else
                    currentStationByGuild.put(event.getGuild().getIdLong(), key);
                // Song titles feed the bot status, voice channel status and ?np
                bot.getRadioMetadata().start(event.getGuild().getIdLong(), url);

                String text = event.getClient().getSuccess() + " Now playing **" + displayName + "** 📻"
                        + (extra == null ? "" : "\n" + extra);
                m.editMessage(bot.getPlayerControls().edit(event.getGuild(), FormatUtil.filter(text), track))
                        .queue(bot.getPlayerControls()::register);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (!playlist.getTracks().isEmpty())
                    trackLoaded(playlist.getTracks().get(0));
            }

            @Override
            public void noMatches() {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                        + " **" + displayName + "** isn't streaming anything I can play right now.")).queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                m.editMessage(FormatUtil.filter(event.getClient().getError() + " Couldn't tune in to **"
                        + displayName + "**: " + exception.getMessage())).queue();
            }
        });
    }
}
