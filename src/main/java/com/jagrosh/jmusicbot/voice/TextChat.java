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

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jmusicbot.Bot;
import java.util.Arrays;
import java.util.Locale;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

/**
 * Talks to the bot by text: @mention it, or reply to one of its messages,
 * in a server text channel, and the same brain as ?chat answers there (see
 * {@link VoiceManager#replyToText}). Commands still go to the command client:
 * "@Gunnabot play x" with a real command name is left alone.
 */
public class TextChat extends ListenerAdapter
{
    private final Bot bot;
    private final CommandClient client;

    public TextChat(Bot bot, CommandClient client)
    {
        this.bot = bot;
        this.client = client;
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event)
    {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage())
            return;
        Message message = event.getMessage();
        User self = event.getJDA().getSelfUser();
        Message replied = message.getReferencedMessage();
        boolean mentioned = message.getMentions().isMentioned(self, Message.MentionType.USER);
        boolean replyToBot = replied != null && replied.getAuthor().getIdLong() == self.getIdLong();
        if (!mentioned && !replyToBot)
            return;
        String raw = message.getContentRaw();
        String prefix = client.getPrefix();
        if (prefix != null && !prefix.isEmpty() && raw.startsWith(prefix))
            return;
        String text = withNames(message, raw.replaceAll("<@!?" + self.getId() + ">", " ")).trim();
        if (isCommand(text))
            return; // "@Gunnabot skip": the command client runs mention-prefixed commands
        bot.getVoiceManager().replyToText(message, text);
    }

    /** Turns other people's mentions into their names, so the LLM reads "@Sam" as Sam. */
    private static String withNames(Message message, String text)
    {
        String out = text;
        for (Member m : message.getMentions().getMembers())
            out = out.replaceAll("<@!?" + m.getId() + ">", "@" + m.getEffectiveName());
        return out.replaceAll("\\s+", " ");
    }

    private boolean isCommand(String text)
    {
        String first = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (first.isEmpty())
            return false;
        for (Command c : client.getCommands())
            if (c.getName().equalsIgnoreCase(first) || Arrays.stream(c.getAliases()).anyMatch(first::equalsIgnoreCase))
                return true;
        return first.equalsIgnoreCase(client.getHelpWord());
    }
}
