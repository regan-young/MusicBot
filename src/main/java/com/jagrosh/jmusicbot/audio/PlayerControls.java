/*
 * Copyright 2026
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
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.settings.Settings;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player buttons (pause, skip, stop, repeat, queue) on the bot's replies.
 *
 * The buttons act on the whole player, not the song a reply mentions, so only
 * one message per guild carries them: the latest reply to a play-type command
 * (or ?np). Registering a new one strips the buttons from the previous one,
 * and they are stripped when playback ends.
 */
public class PlayerControls {
    public static final String ID_PREFIX = "player:";
    public static final String PAUSE = ID_PREFIX + "pause";
    public static final String SKIP = ID_PREFIX + "skip";
    public static final String STOP = ID_PREFIX + "stop";
    public static final String REPEAT = ID_PREFIX + "repeat";
    public static final String QUEUE = ID_PREFIX + "queue";

    private final Bot bot;
    // guild id -> {channel id, message id} of the message carrying the buttons
    private final Map<Long, long[]> controlByGuild = new ConcurrentHashMap<>();

    public PlayerControls(Bot bot) {
        this.bot = bot;
    }

    /** The button row, reflecting the guild's current pause and repeat state. */
    public List<ActionRow> rows(Guild guild) {
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        boolean paused = handler != null && handler.getPlayer().isPaused();
        RepeatMode repeat = bot.getSettingsManager().getSettings(guild).getRepeatMode();

        Button pause = paused
                ? Button.success(PAUSE, "Resume").withEmoji(Emoji.fromUnicode("▶️"))
                : Button.secondary(PAUSE, "Pause").withEmoji(Emoji.fromUnicode("⏸️"));
        Button skip = Button.secondary(SKIP, "Skip").withEmoji(Emoji.fromUnicode("⏭️"));
        Button stop = Button.danger(STOP, "Stop").withEmoji(Emoji.fromUnicode("⏹️"));
        String repeatLabel = repeat == RepeatMode.OFF ? "Repeat" : "Repeat: " + repeat.getUserFriendlyName();
        Button repeatButton = (repeat == RepeatMode.OFF ? Button.secondary(REPEAT, repeatLabel) : Button.primary(REPEAT, repeatLabel))
                .withEmoji(Emoji.fromUnicode(repeat == RepeatMode.SINGLE ? "🔂" : "🔁"));
        Button queue = Button.secondary(QUEUE, "Queue").withEmoji(Emoji.fromUnicode("📜"));
        return List.of(ActionRow.of(pause, skip, stop, repeatButton, queue));
    }

    /** The reply card: the command's text, with the track's art beside it. */
    public MessageEmbed card(Guild guild, String text, AudioTrack track) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(guild.getSelfMember().getColor())
                .setDescription(text);
        String art = TrackArt.artworkUrl(track, bot.getRadioMetadata());
        if (art != null)
            eb.setThumbnail(art);
        return eb.build();
    }

    /** A new reply carrying the card and buttons; pass the sent message to {@link #register}. */
    public MessageCreateData message(Guild guild, String text, AudioTrack track) {
        return new MessageCreateBuilder()
                .setEmbeds(card(guild, text, track))
                .setComponents(rows(guild))
                .build();
    }

    /** Turns an existing reply into the card and buttons; pass the result to {@link #register}. */
    public MessageEditData edit(Guild guild, String text, AudioTrack track) {
        return new MessageEditBuilder()
                .setContent("")
                .setEmbeds(card(guild, text, track))
                .setComponents(rows(guild))
                .build();
    }

    /** Makes this the guild's control message, stripping the buttons from the previous one. */
    public void register(Message message) {
        if (!message.isFromGuild())
            return;
        long[] previous = controlByGuild.put(message.getGuild().getIdLong(),
                new long[] { message.getChannel().getIdLong(), message.getIdLong() });
        bot.getNowplayingHandler().onControlsMoved(message.getGuild(), message.getIdLong());
        if (previous != null && previous[1] != message.getIdLong())
            setButtons(message.getGuild(), previous, Collections.emptyList());
    }

    /** Removes the buttons from the guild's control message (playback ended). */
    public void strip(Guild guild) {
        long[] current = controlByGuild.remove(guild.getIdLong());
        if (current != null)
            setButtons(guild, current, Collections.emptyList());
    }

    /** Updates the control message's buttons after a pause or repeat change. */
    public void refresh(Guild guild) {
        long[] current = controlByGuild.get(guild.getIdLong());
        if (current != null)
            setButtons(guild, current, rows(guild));
    }

    private void setButtons(Guild guild, long[] ref, List<ActionRow> rows) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, ref[0]);
        if (channel != null)
            channel.editMessageComponentsById(ref[1], rows).queue(null, t -> { });
    }
}
