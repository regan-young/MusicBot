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
package com.jagrosh.jmusicbot.voice;

import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jdautilities.command.CommandEvent;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
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
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

/**
 * A command invocation that came from speech rather than a message, so a
 * spoken request runs the real command (?play, ?radio, ...) with its usual
 * checks, as the person who said it. Replies go to the text channel
 * ?interact was started in, like any command output; text replies are also
 * passed to a listener so error messages can be spoken. Modelled on
 * {@link com.jagrosh.jmusicbot.commands.InteractionCommandEvent}.
 */
public class VoiceCommandEvent extends CommandEvent
{
    private static final AtomicLong RESPONSE_NUMBERS = new AtomicLong();

    private final Member member;
    private final MessageChannel channel;
    private final Consumer<String> onText;
    private final long responseNumber = RESPONSE_NUMBERS.incrementAndGet();

    public VoiceCommandEvent(Member member, MessageChannel channel, String args, CommandClient client, Consumer<String> onText)
    {
        super(null, client.getPrefix(), args, client);
        this.member = member;
        this.channel = channel;
        this.onText = onText;
    }

    // ------------------------------------------------------------------ replies

    private void send(MessageCreateData data, Consumer<Message> success, Consumer<Throwable> failure)
    {
        if (!data.getContent().isEmpty())
            onText.accept(data.getContent());
        channel.sendMessage(data).queue(success, failure != null ? failure : t -> { });
    }

    private static MessageCreateData text(String content)
    {
        return new MessageCreateBuilder().setContent(content).build();
    }

    private static MessageCreateData embed(MessageEmbed embed)
    {
        return new MessageCreateBuilder().setEmbeds(embed).build();
    }

    @Override public void reply(String message) { send(text(message), null, null); }
    @Override public void reply(String message, Consumer<Message> success) { send(text(message), success, null); }
    @Override public void reply(String message, Consumer<Message> success, Consumer<Throwable> failure) { send(text(message), success, failure); }
    @Override public void reply(MessageEmbed embed) { send(embed(embed), null, null); }
    @Override public void reply(MessageEmbed embed, Consumer<Message> success) { send(embed(embed), success, null); }
    @Override public void reply(MessageEmbed embed, Consumer<Message> success, Consumer<Throwable> failure) { send(embed(embed), success, failure); }
    @Override public void reply(MessageCreateData message) { send(message, null, null); }
    @Override public void reply(MessageCreateData message, Consumer<Message> success) { send(message, success, null); }
    @Override public void reply(MessageCreateData message, Consumer<Message> success, Consumer<Throwable> failure) { send(message, success, failure); }
    @Override public void replyFormatted(String format, Object... args) { reply(String.format(format, args)); }
    @Override public void replyOrAlternate(MessageEmbed embed, String alternateMessage) { reply(embed); }
    @Override public void replySuccess(String message) { reply(getClient().getSuccess() + " " + message); }
    @Override public void replySuccess(String message, Consumer<Message> queue) { reply(getClient().getSuccess() + " " + message, queue); }
    @Override public void replyWarning(String message) { reply(getClient().getWarning() + " " + message); }
    @Override public void replyWarning(String message, Consumer<Message> queue) { reply(getClient().getWarning() + " " + message, queue); }
    @Override public void replyError(String message) { reply(getClient().getError() + " " + message); }
    @Override public void replyError(String message, Consumer<Message> queue) { reply(getClient().getError() + " " + message, queue); }

    // there's no message to react to; the spoken confirmation covers it
    @Override public void reactSuccess() {}
    @Override public void reactWarning() {}
    @Override public void reactError() {}

    // answer in the channel rather than by DM, like slash commands do
    @Override public void replyInDm(String message) { reply(message); }
    @Override public void replyInDm(String message, Consumer<Message> success) { reply(message, success); }
    @Override public void replyInDm(String message, Consumer<Message> success, Consumer<Throwable> failure) { reply(message, success, failure); }
    @Override public void replyInDm(MessageEmbed embed) { reply(embed); }
    @Override public void replyInDm(MessageEmbed embed, Consumer<Message> success) { reply(embed, success); }
    @Override public void replyInDm(MessageCreateData message) { reply(message); }
    @Override public void replyInDm(MessageCreateData message, Consumer<Message> success) { reply(message, success); }

    @Override public void linkId(Message message) {}

    // ------------------------------------------------------------------ context

    @Override public Message getMessage() { return null; }
    @Override public User getAuthor() { return member.getUser(); }
    @Override public Member getMember() { return member; }
    @Override public Guild getGuild() { return member.getGuild(); }
    @Override public JDA getJDA() { return member.getJDA(); }
    @Override public SelfUser getSelfUser() { return member.getJDA().getSelfUser(); }
    @Override public Member getSelfMember() { return member.getGuild().getSelfMember(); }
    @Override public MessageChannel getChannel() { return channel; }
    @Override public ChannelType getChannelType() { return channel.getType(); }
    @Override public boolean isFromType(ChannelType type) { return channel.getType() == type; }
    @Override public TextChannel getTextChannel() { return channel instanceof TextChannel ? (TextChannel) channel : null; }
    @Override public GuildMessageChannel getGuildChannel() { return channel instanceof GuildMessageChannel ? (GuildMessageChannel) channel : null; }
    @Override public long getResponseNumber() { return responseNumber; }

    @Override
    public boolean isOwner()
    {
        String id = member.getUser().getId();
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
