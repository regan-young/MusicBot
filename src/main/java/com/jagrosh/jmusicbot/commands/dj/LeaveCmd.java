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
package com.jagrosh.jmusicbot.commands.dj;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.commands.DJCommand;

/**
 * Stops the music, ends ?chat and leaves the voice channel. While ?chat is
 * on, this (or asking out loud) is the only way the bot leaves: ?stop and an
 * empty queue keep it in the channel.
 */
public class LeaveCmd extends DJCommand
{
    public LeaveCmd(Bot bot)
    {
        super(bot);
        this.name = "leave";
        this.help = "stops the music, stops listening and leaves the voice channel";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.bePlaying = false;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler != null)
            handler.stopAndClear();
        // stops ?chat too
        bot.closeAudioConnection(event.getGuild().getIdLong());
        event.reply(event.getClient().getSuccess() + " Left the voice channel.");
    }
}
