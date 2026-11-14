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
package com.jagrosh.jmusicbot.commands.admin;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.AdminCommand;
import com.jagrosh.jmusicbot.settings.Settings;

/**
 * Switches the spoken "Oh hey, name" greeting for people joining the voice channel.
 */
public class GreetCmd extends AdminCommand
{
    public GreetCmd(Bot bot)
    {
        super();
        this.name = "greet";
        this.help = "greets people out loud when they join the voice channel";
        this.arguments = "[on|off]";
        this.aliases = bot.getConfig().getAliases(this.name);
    }

    @Override
    protected void execute(CommandEvent event)
    {
        Settings settings = event.getClient().getSettingsFor(event.getGuild());
        String args = event.getArgs().trim().toLowerCase();
        if (args.isEmpty())
        {
            event.reply("Greetings are `" + (settings.isGreet() ? "on" : "off") + "`.");
            return;
        }
        if (!args.equals("on") && !args.equals("off"))
        {
            event.replyError("Use `" + event.getClient().getPrefix() + name + " on` or `" + event.getClient().getPrefix() + name + " off`.");
            return;
        }
        settings.setGreet(args.equals("on"));
        event.replySuccess("Greetings are now `" + args + "`.");
    }
}
