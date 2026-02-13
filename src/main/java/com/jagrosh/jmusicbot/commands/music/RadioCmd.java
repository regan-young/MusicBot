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
import net.dv8tion.jda.api.entities.Activity;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class RadioCmd extends MusicCommand {
    private final Map<String, String> stations;
    private final Map<String, String> stationDisplayNames;

    public RadioCmd(Bot bot) {
        super(bot);
        this.name = "radio";
        this.help = "plays a live radio station";
        this.arguments = "<station name|list>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;

        this.stations = new HashMap<>();
        this.stationDisplayNames = new HashMap<>();

        // RNZ
        addStation("rnznational", "RNZ National", "https://radionz.streamguys1.com/national/national/playlist.m3u8");

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
    }

    private void addStation(String key, String displayName, String url) {
        stations.put(key, url);
        stationDisplayNames.put(key, displayName);
    }

    /**
     * Checks if the currently playing track is a radio stream.
     */
    public static boolean isRadioStream(AudioHandler handler) {
        if (handler == null || handler.getPlayer().getPlayingTrack() == null) {
            return false;
        }
        String uri = handler.getPlayer().getPlayingTrack().getInfo().uri;
        return uri != null && (uri.contains("streamguys") || uri.contains("streamtheworld"));
    }

    @Override
    public void doCommand(CommandEvent event) {
        String args = event.getArgs().trim().toLowerCase();

        if (args.isEmpty() || args.equals("list")) {
            StringBuilder builder = new StringBuilder();
            builder.append("__**Available Radio Stations:**__\n");
            for (String stationKey : stations.keySet().stream().sorted().collect(Collectors.toList())) {
                builder.append("`").append(stationKey).append("` - ").append(stationDisplayNames.get(stationKey))
                        .append("\n");
            }
            builder.append("\nType `").append(event.getClient().getPrefix()).append(name)
                    .append(" <station name>` to play.");
            event.reply(builder.toString());
            return;
        }

        if (stations.containsKey(args)) {
            String url = stations.get(args);
            String displayName = stationDisplayNames.get(args);
            event.reply(event.getClient().getSuccess() + " Loading **" + displayName + "**...",
                    m -> bot.getPlayerManager().loadItemOrdered(event.getGuild(), url, new AudioLoadResultHandler() {
                        @Override
                        public void trackLoaded(AudioTrack track) {
                            AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager()
                                    .getSendingHandler();

                            boolean wasRadio = isRadioStream(handler);

                            // Add the new track to the queue FIRST, then stop the radio.
                            // If we stop first, onTrackEnd sees an empty queue and disconnects.
                            handler.addTrack(
                                    new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));

                            if (wasRadio) {
                                handler.getPlayer().stopTrack();
                            }

                            // Set bot status to show the station name
                            if (bot.getConfig().getSongInStatus()) {
                                event.getJDA().getPresence()
                                        .setActivity(Activity.listening("\uD83D\uDCFB " + displayName));
                            }

                            m.editMessage(FormatUtil.filter(event.getClient().getSuccess() + " Now playing **"
                                    + displayName + "** \uD83D\uDCFB")).queue();
                        }

                        @Override
                        public void playlistLoaded(AudioPlaylist playlist) {
                            if (!playlist.getTracks().isEmpty()) {
                                trackLoaded(playlist.getTracks().get(0));
                            }
                        }

                        @Override
                        public void noMatches() {
                            m.editMessage(FormatUtil.filter(
                                    event.getClient().getWarning() + " No matching stream found for **" + displayName
                                            + "**."))
                                    .queue();
                        }

                        @Override
                        public void loadFailed(FriendlyException exception) {
                            m.editMessage(FormatUtil.filter(event.getClient().getError() + " Failed to load station: "
                                    + exception.getMessage())).queue();
                        }
                    }));
        } else {
            event.replyError("Station `" + args + "` not found. Type `" + event.getClient().getPrefix() + name
                    + " list` to see available stations.");
        }
    }
}
