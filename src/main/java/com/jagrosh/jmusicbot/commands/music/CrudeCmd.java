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
 * ?crude on|off: the bot swears and roasts people, in any channel. Nothing
 * sexual, which stays with ?explicit in age-restricted channels. It can also
 * be asked for out loud during ?chat, or by @mention.
 */
public class CrudeCmd extends Command
{
    private final Bot bot;

    public CrudeCmd(Bot bot)
    {
        this.bot = bot;
        this.name = "crude";
        this.arguments = "<on|off>";
        this.help = "turns my crude mode on or off: swearing and roasts, nothing sexual";
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
            boolean on = bot.getVoiceManager().isCrude(event.getGuild(), event.getChannel());
            event.reply("Crude mode is " + (on ? "**on**" : "**off**") + " here. `" + event.getClient().getPrefix()
                    + name + " on` or `off` to change it.");
            return;
        }
        if (!arg.equals("on") && !arg.equals("off"))
        {
            event.replyError("Use `" + event.getClient().getPrefix() + name + " on` or `" + event.getClient().getPrefix()
                    + name + " off`.");
            return;
        }
        String result = bot.getVoiceManager().setCrude(event.getGuild(), event.getChannel(), arg.equals("on"));
        event.reply(result);
        // say it too if ?chat is on in this guild
        if (bot.getVoiceManager().isListening(event.getGuild().getIdLong()))
            bot.getVoiceManager().speak(event.getGuild(), result);
    }
}
