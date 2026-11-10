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
package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.voice.LastSeen;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ?lastseen <name or @mention>: when someone last sent a message or was in
 * voice. Online status isn't available (it needs the privileged presence
 * intent), so this only covers what the bot has seen since it began recording.
 */
public class LastSeenCmd extends Command
{
    private static final Pattern MENTION = Pattern.compile("<@!?(\\d+)>");

    private final Bot bot;

    public LastSeenCmd(Bot bot)
    {
        this.bot = bot;
        this.name = "lastseen";
        this.arguments = "<name>";
        this.help = "shows when someone last sent a message or was in voice";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = true;
        this.category = new Category("Music");
    }

    @Override
    protected void execute(CommandEvent event)
    {
        String arg = event.getArgs().trim();
        if (arg.isEmpty())
        {
            event.replyError("Tell me who: `" + event.getClient().getPrefix() + name + " <name>`.");
            return;
        }
        LastSeen.Seen seen;
        Matcher mention = MENTION.matcher(arg);
        seen = mention.matches() ? bot.getLastSeen().byId(Long.parseLong(mention.group(1))) : bot.getLastSeen().byName(arg);
        if (seen == null)
        {
            event.reply("I haven't seen anyone called " + arg.replaceAll("<@!?\\d+>", "that person")
                    + " send a message or join voice since I started keeping track. I can't see online status.");
            return;
        }
        long now = System.currentTimeMillis();
        String last = seen.messageAt() >= seen.voiceAt()
                ? "sent a message " + LastSeen.ago(now - seen.messageAt())
                : "was in voice " + LastSeen.ago(now - seen.voiceAt());
        event.reply("I last saw **" + seen.name() + "** " + last + ". I can't see online status, only messages and voice.");
    }
}
