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
import java.util.Locale;

/**
 * ?explicit on|off: the bot's explicit mode, in channels Discord marks
 * age-restricted only (an 18+ voice channel for ?chat, or an 18+ text
 * channel). It can also be asked for out loud during ?chat.
 */
public class ExplicitCmd extends Command
{
    private final Bot bot;

    public ExplicitCmd(Bot bot)
    {
        this.bot = bot;
        this.name = "explicit";
        this.arguments = "<on|off>";
        this.help = "turns my explicit mode on or off (age-restricted channels only)";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = true;
        this.category = new Category("Music");
    }

    @Override
    protected void execute(CommandEvent event)
    {
        String arg = event.getArgs().trim().toLowerCase(Locale.ROOT);
        if (arg.isEmpty())
        {
            boolean on = bot.getVoiceManager().isExplicit(event.getGuild(), event.getChannel());
            event.reply("Explicit mode is " + (on ? "**on**" : "**off**") + " here. `" + event.getClient().getPrefix()
                    + name + " on` or `off` to change it.");
            return;
        }
        if (!arg.equals("on") && !arg.equals("off"))
        {
            event.replyError("Use `" + event.getClient().getPrefix() + name + " on` or `" + event.getClient().getPrefix()
                    + name + " off`.");
            return;
        }
        String result = bot.getVoiceManager().setExplicit(event.getGuild(), event.getChannel(), arg.equals("on"));
        event.reply(result);
        // say it too if ?chat is on in this guild
        if (bot.getVoiceManager().isListening(event.getGuild().getIdLong()))
            bot.getVoiceManager().speak(event.getGuild(), result);
    }
}
