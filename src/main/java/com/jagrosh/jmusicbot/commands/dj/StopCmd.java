/*
 * Copyright 2018 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.commands.dj;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.commands.DJCommand;
import java.util.Arrays;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class StopCmd extends DJCommand
{
    public StopCmd(Bot bot)
    {
        super(bot);
        this.name = "stop";
        this.help = "stops the current song and clears the queue";
        // "leave" is its own command now; older configs still list it as an
        // alias here, and a duplicate would stop the command client starting
        this.aliases = Arrays.stream(bot.getConfig().getAliases(this.name))
                .filter(a -> !a.equalsIgnoreCase("leave"))
                .toArray(String[]::new);
        this.bePlaying = false;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        handler.stopAndClear();
        if (bot.getVoiceManager().isListening(event.getGuild().getIdLong()))
        {
            // ?chat is on: stay in the channel and keep listening
            event.reply(event.getClient().getSuccess()+" The player has stopped and the queue has been cleared. "
                    + "I'm staying for the chat; `"+event.getClient().getPrefix()+"leave` when you're done.");
            return;
        }
        event.getGuild().getAudioManager().closeAudioConnection();
        event.reply(event.getClient().getSuccess()+" The player has stopped and the queue has been cleared.");
    }
}
