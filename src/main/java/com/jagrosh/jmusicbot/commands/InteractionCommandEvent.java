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
package com.jagrosh.jmusicbot.commands;

import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.command.CommandEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Lets the existing prefix commands run from a slash command or a button.
 *
 * The commands are written against {@link CommandEvent}, which wraps a
 * received message. This stands in for that message: the interaction supplies
 * the user, guild and channel, and replies go to the interaction instead of
 * the channel. The first reply fills in the deferred response; later replies
 * are sent as follow-ups (or ordinary channel messages if public).
 *
 * The caller must defer the interaction before running the command.
 */
public class InteractionCommandEvent extends CommandEvent {
    private final IReplyCallback interaction;
    private final boolean ephemeral;
    private final AtomicBoolean replied = new AtomicBoolean(false);

    public InteractionCommandEvent(IReplyCallback interaction, String args, CommandClient client, boolean ephemeral) {
        super(null, "/", args, client);
        this.interaction = interaction;
        this.ephemeral = ephemeral;
    }

    /**
     * Commands that answer some other way (a reaction menu, a message sent
     * straight to the channel) never reply; drop the "thinking..." placeholder
     * if nothing has filled it in by then.
     */
    public void cleanUpIfUnanswered() {
        interaction.getJDA().getGatewayPool().schedule(() -> {
            if (replied.compareAndSet(false, true)) {
                interaction.getHook().deleteOriginal().queue(null, t -> { });
            }
        }, 20, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ replies

    private void send(MessageCreateData data, Consumer<Message> success, Consumer<Throwable> failure) {
        Consumer<Throwable> onError = failure != null ? failure : t -> { };
        if (replied.compareAndSet(false, true)) {
            interaction.getHook().editOriginal(MessageEditData.fromCreateData(data)).queue(success, onError);
        } else if (ephemeral) {
            interaction.getHook().sendMessage(data).setEphemeral(true).queue(success, onError);
        } else {
            getChannel().sendMessage(data).queue(success, onError);
        }
    }

    private static MessageCreateData text(String content) {
        return new MessageCreateBuilder().setContent(content).build();
    }

    private static MessageCreateData embed(MessageEmbed embed) {
        return new MessageCreateBuilder().setEmbeds(embed).build();
    }

    @Override
    public void reply(String message) {
        send(text(message), null, null);
    }

    @Override
    public void reply(String message, Consumer<Message> success) {
        send(text(message), success, null);
    }

    @Override
    public void reply(String message, Consumer<Message> success, Consumer<Throwable> failure) {
        send(text(message), success, failure);
    }

    @Override
    public void reply(MessageEmbed embed) {
        send(embed(embed), null, null);
    }

    @Override
    public void reply(MessageEmbed embed, Consumer<Message> success) {
        send(embed(embed), success, null);
    }

    @Override
    public void reply(MessageEmbed embed, Consumer<Message> success, Consumer<Throwable> failure) {
        send(embed(embed), success, failure);
    }

    @Override
    public void reply(MessageCreateData message) {
        send(message, null, null);
    }

    @Override
    public void reply(MessageCreateData message, Consumer<Message> success) {
        send(message, success, null);
    }

    @Override
    public void reply(MessageCreateData message, Consumer<Message> success, Consumer<Throwable> failure) {
        send(message, success, failure);
    }

    @Override
    public void replyFormatted(String format, Object... args) {
        reply(String.format(format, args));
    }

    @Override
    public void replyOrAlternate(MessageEmbed embed, String alternateMessage) {
        reply(embed);
    }

    @Override
    public void replySuccess(String message) {
        reply(getClient().getSuccess() + " " + message);
    }

    @Override
    public void replySuccess(String message, Consumer<Message> queue) {
        reply(getClient().getSuccess() + " " + message, queue);
    }

    @Override
    public void replyWarning(String message) {
        reply(getClient().getWarning() + " " + message);
    }

    @Override
    public void replyWarning(String message, Consumer<Message> queue) {
        reply(getClient().getWarning() + " " + message, queue);
    }

    @Override
    public void replyError(String message) {
        reply(getClient().getError() + " " + message);
    }

    @Override
    public void replyError(String message, Consumer<Message> queue) {
        reply(getClient().getError() + " " + message, queue);
    }

    // A slash command has no message to react to, so say it instead
    @Override
    public void reactSuccess() {
        reply(getClient().getSuccess());
    }

    @Override
    public void reactWarning() {
        reply(getClient().getWarning());
    }

    @Override
    public void reactError() {
        reply(getClient().getError());
    }

    // Interaction replies are already private to the user when ephemeral, and
    // DMs are often closed; answer in place rather than by DM
    @Override
    public void replyInDm(String message) {
        send(text(message), null, null);
    }

    @Override
    public void replyInDm(String message, Consumer<Message> success) {
        send(text(message), success, null);
    }

    @Override
    public void replyInDm(String message, Consumer<Message> success, Consumer<Throwable> failure) {
        send(text(message), success, failure);
    }

    @Override
    public void replyInDm(MessageEmbed embed) {
        send(embed(embed), null, null);
    }

    @Override
    public void replyInDm(MessageEmbed embed, Consumer<Message> success) {
        send(embed(embed), success, null);
    }

    @Override
    public void replyInDm(MessageCreateData message) {
        send(message, null, null);
    }

    @Override
    public void replyInDm(MessageCreateData message, Consumer<Message> success) {
        send(message, success, null);
    }

    @Override
    public void linkId(Message message) {
        // linked message ids are for prefix-command edits/deletes; nothing to link
    }

    // ------------------------------------------------------------------ context

    /** There is no command message; callers must handle null. */
    @Override
    public Message getMessage() {
        return null;
    }

    @Override
    public User getAuthor() {
        return interaction.getUser();
    }

    @Override
    public Member getMember() {
        return interaction.getMember();
    }

    @Override
    public Guild getGuild() {
        return interaction.getGuild();
    }

    @Override
    public JDA getJDA() {
        return interaction.getJDA();
    }

    @Override
    public SelfUser getSelfUser() {
        return interaction.getJDA().getSelfUser();
    }

    @Override
    public Member getSelfMember() {
        Guild guild = getGuild();
        return guild == null ? null : guild.getSelfMember();
    }

    @Override
    public MessageChannel getChannel() {
        return interaction.getMessageChannel();
    }

    @Override
    public ChannelType getChannelType() {
        return interaction.getChannelType();
    }

    @Override
    public boolean isFromType(ChannelType type) {
        return interaction.getChannelType() == type;
    }

    @Override
    public TextChannel getTextChannel() {
        return interaction.getChannel() instanceof TextChannel ? (TextChannel) interaction.getChannel() : null;
    }

    @Override
    public GuildMessageChannel getGuildChannel() {
        return interaction.getChannel() instanceof GuildMessageChannel
                ? (GuildMessageChannel) interaction.getChannel() : null;
    }

    @Override
    public long getResponseNumber() {
        return interaction.getIdLong();
    }

    @Override
    public boolean isOwner() {
        String id = interaction.getUser().getId();
        if (id.equals(getClient().getOwnerId()))
            return true;
        if (getClient().getCoOwnerIds() == null)
            return false;
        for (String coOwner : getClient().getCoOwnerIds())
            if (coOwner.equals(id))
                return true;
        return false;
    }
}
