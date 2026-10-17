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

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.MusicCommand;

/**
 * Speaks text in the voice channel (text-to-speech). Music pauses for it.
 */
public class SayCmd extends MusicCommand
{
    private static final int MAX_CHARS = 500;

    public SayCmd(Bot bot)
    {
        super(bot);
        this.name = "say";
        this.arguments = "<text>";
        this.help = "says something out loud in the voice channel";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        if (!bot.getVoiceManager().isEnabled())
        {
            event.replyError("Voice isn't set up: `voiceapi` is empty in the config.");
            return;
        }
        String text = event.getArgs().trim();
        if (text.isEmpty())
        {
            event.replyError("Tell me what to say, e.g. `" + event.getClient().getPrefix() + name + " hello everyone`");
            return;
        }
        if (text.length() > MAX_CHARS)
        {
            event.replyError("That's too long; keep it under " + MAX_CHARS + " characters.");
            return;
        }
        bot.getVoiceManager().speak(event.getGuild(), text);
        event.reactSuccess();
    }
}
