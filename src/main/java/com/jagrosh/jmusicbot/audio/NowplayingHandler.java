/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.audio;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.entities.Pair;
import com.jagrosh.jmusicbot.settings.Settings;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.channel.attribute.IVoiceStatusChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class NowplayingHandler {
    private final Bot bot;
    private static final Logger LOG = LoggerFactory.getLogger(NowplayingHandler.class);
    // Written from JDA threads while the scheduler iterates it
    private final Map<Long, Pair<Long, Long>> lastNP; // guild -> channel,message
    private final Map<Long, String> voiceStatus = new ConcurrentHashMap<>(); // guild -> status last set
    private final Set<Long> warnedNoStatusPermission = ConcurrentHashMap.newKeySet();

    public NowplayingHandler(Bot bot) {
        this.bot = bot;
        this.lastNP = new ConcurrentHashMap<>();
    }

    public void init() {
        bot.getThreadpool().scheduleWithFixedDelay(() -> {
                // An exception escaping a scheduled task cancels all future runs
                try {
                    updateAll();
                } catch (Exception e) {
                    LOG.warn("Now-playing update failed", e);
                }
            }, 0, 5, TimeUnit.SECONDS);
    }

    public void setLastNPMessage(Message m) {
        var channel = m.getChannel();
        if (channel instanceof TextChannel) {
            TextChannel tc = (TextChannel) channel;
            lastNP.put(m.getGuild().getIdLong(), new Pair<>(tc.getIdLong(), m.getIdLong()));
        }
    }

    /** The player buttons moved to another message: stop refreshing an older ?np (which would restore its buttons). */
    public void onControlsMoved(Guild guild, long messageId) {
        Pair<Long, Long> pair = lastNP.get(guild.getIdLong());
        if (pair != null && pair.getValue() != messageId)
            lastNP.remove(guild.getIdLong());
    }

    public void clearLastNPMessage(Guild guild) {
        lastNP.remove(guild.getIdLong());
    }

    private void updateAll() {
        Set<Long> toRemove = new HashSet<>();
        for (long guildId : lastNP.keySet()) {
            Guild guild = bot.getJDA().getGuildById(guildId);
            if (guild == null) {
                toRemove.add(guildId);
                continue;
            }
            Pair<Long, Long> pair = lastNP.get(guildId);
            TextChannel tc = guild.getTextChannelById(pair.getKey());
            if (tc == null) {
                toRemove.add(guildId);
                continue;
            }
            AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
            MessageCreateData msg = handler.getNowPlaying(bot.getJDA());
            if (msg == null) {
                msg = handler.getNoMusicPlaying(bot.getJDA());
                toRemove.add(guildId);
            }
            try {
                tc.editMessageById(pair.getValue().longValue(), MessageEditData.fromCreateData(msg))
                        .queue(
                                m -> {
                                }, // on success
                                t -> lastNP.remove(guildId) // on failure
                        );
            } catch (Exception e) {
                toRemove.add(guildId);
            }
        }
        toRemove.forEach(id -> lastNP.remove(id));
    }

    // "event"-based methods
    public void onTrackUpdate(Guild guild, AudioTrack track) {
        if (guild != null) {
            updateVoiceStatus(guild, track, true);
            if (track == null)
                bot.getPlayerControls().strip(guild);
        }
        updatePresence(track);
    }

    /** "🎵 Artist - Title" or "📻 Station · Artist - Title". */
    private String statusText(AudioTrack track) {
        String radio = bot.getRadioMetadata().getLabel(track);
        if (radio != null)
            return radio;
        String title = track.getInfo().title;
        return "\uD83C\uDFB5 " + (title == null || title.isBlank() ? "Music" : title);
    }

    /**
     * Shows the current song as the voice channel's status (the line under its
     * name). Unlike the bot's own status this is per guild. Needs the "Set
     * Voice Channel Status" permission.
     */
    private void updateVoiceStatus(Guild guild, AudioTrack track, boolean retryIfConnecting) {
        GuildVoiceState vs = guild.getSelfMember().getVoiceState();
        AudioChannelUnion channel = vs == null ? null : vs.getChannel();
        if (channel == null && track != null && retryIfConnecting) {
            // The first song starts while the voice connection is still being made
            bot.getThreadpool().schedule(() -> {
                AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
                AudioTrack now = handler == null ? null : handler.getPlayer().getPlayingTrack();
                if (now != null)
                    updateVoiceStatus(guild, now, false);
            }, 5, TimeUnit.SECONDS);
            return;
        }
        if (!(channel instanceof VoiceChannel))
            return;
        VoiceChannel vc = (VoiceChannel) channel;
        String status = track == null ? "" : statusText(track);
        if (status.length() > IVoiceStatusChannel.MAX_STATUS_LENGTH)
            status = status.substring(0, IVoiceStatusChannel.MAX_STATUS_LENGTH - 1) + "\u2026";
        if (status.equals(voiceStatus.get(guild.getIdLong())))
            return;
        if (!guild.getSelfMember().hasPermission(vc, Permission.VOICE_SET_STATUS)) {
            if (warnedNoStatusPermission.add(guild.getIdLong()))
                LOG.warn("No \"Set Voice Channel Status\" permission in {}; not showing songs in the voice channel status", guild.getName());
            return;
        }
        voiceStatus.put(guild.getIdLong(), status);
        vc.modifyStatus(status).queue(null, t -> {
            voiceStatus.remove(guild.getIdLong());
            LOG.debug("Could not set voice channel status in {}", guild.getName(), t);
        });
    }

    private void updatePresence(AudioTrack track) {
        // update bot status if applicable
        if (bot.getConfig().getSongInStatus()) {
            if (track != null && bot.getJDA().getGuilds().stream()
                    .filter(g -> {
                        var vs = g.getSelfMember().getVoiceState();
                        return vs != null && vs.getChannel() != null;
                    }).count() <= 1) {
                String title = bot.getRadioMetadata().getLabel(track);
                if (title == null)
                    title = track.getInfo().title;
                if (title == null || title.isBlank() || title.equalsIgnoreCase("Unspecified description"))
                    title = "Radio";
                bot.getJDA().getPresence().setActivity(Activity.listening(title.length() > 128 ? title.substring(0, 127) + "\u2026" : title));
            } else
                bot.resetGame();
        }
    }

    public void onMessageDelete(Guild guild, long messageId) {
        Pair<Long, Long> pair = lastNP.get(guild.getIdLong());
        if (pair == null)
            return;
        if (pair.getValue() == messageId)
            lastNP.remove(guild.getIdLong());
    }
}
