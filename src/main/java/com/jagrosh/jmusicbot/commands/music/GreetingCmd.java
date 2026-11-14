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
import com.jagrosh.jmusicbot.voice.Greetings;

/**
 * ?greeting <text>: what the bot says when you join its voice channel, with
 * {name} for your name. ?greeting on its own shows yours; ?greeting off goes
 * back to the usual long welcome speech. Greetings only play when an admin has ?greet on.
 */
public class GreetingCmd extends Command
{
    private final Bot bot;

    public GreetingCmd(Bot bot)
    {
        this.bot = bot;
        this.name = "greeting";
        this.arguments = "[text|off]";
        this.help = "sets what I say when you join the voice channel; {name} is your name";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.guildOnly = true;
        this.category = new Category("Music");
    }

    @Override
    protected void execute(CommandEvent event)
    {
        Greetings greetings = bot.getVoiceManager().getGreetings();
        long id = event.getAuthor().getIdLong();
        String arg = event.getArgs().trim();
        String prefix = event.getClient().getPrefix();
        if (arg.isEmpty())
        {
            String mine = greetings.get(id);
            event.reply(mine.isEmpty() ? "You get a long, over-the-top welcome speech. Set your own with `"
                    + prefix + name + " <text>`; `{name}` becomes your name." : "Your greeting: " + mine);
            return;
        }
        if (arg.equalsIgnoreCase("off") || arg.equalsIgnoreCase("clear") || arg.equalsIgnoreCase("reset"))
        {
            greetings.set(id, event.getMember().getEffectiveName(), "");
            event.replySuccess("Back to the usual hello.");
            return;
        }
        String kept = greetings.set(id, event.getMember().getEffectiveName(), arg);
        event.replySuccess("Next time you join voice I'll say: " + kept
                + (arg.length() > Greetings.MAX_LENGTH ? " (cut to " + Greetings.MAX_LENGTH + " characters)" : ""));
    }
}
