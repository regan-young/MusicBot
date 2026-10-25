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

public class KaraokeCmd extends DJCommand
{
    public KaraokeCmd(Bot bot)
    {
        super(bot);
        this.name = "karaoke";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.help = "removes the vocals from songs (centre channel removal)";
        this.arguments = "[on|off]";
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        String args = event.getArgs();
        boolean on;
        if(args.isEmpty())
            on = !handler.isKaraoke();
        else if(args.equalsIgnoreCase("on") || args.equalsIgnoreCase("true"))
            on = true;
        else if(args.equalsIgnoreCase("off") || args.equalsIgnoreCase("false"))
            on = false;
        else
        {
            event.replyError("Valid options are `on` or `off` (or leave empty to toggle)");
            return;
        }
        handler.setKaraoke(on);
        event.replySuccess("Karaoke mode is now `" + (on ? "on" : "off") + "`"
                + (on ? ". Vocals are removed as well as the song allows, and the sound goes mono." : ""));
    }
}
