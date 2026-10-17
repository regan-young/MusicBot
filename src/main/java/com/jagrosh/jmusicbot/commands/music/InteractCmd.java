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
import com.jagrosh.jmusicbot.voice.VoiceManager;

/**
 * Toggles voice interaction: the bot listens in its voice channel, posts what
 * it hears, and answers out loud or runs playback commands.
 */
public class InteractCmd extends MusicCommand
{
    public InteractCmd(Bot bot)
    {
        super(bot);
        this.name = "interact";
        this.help = "talk with me in voice: I listen, answer out loud and take music requests (run again to stop)";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        VoiceManager voice = bot.getVoiceManager();
        if (!voice.isEnabled())
        {
            event.replyError("Voice isn't set up: `voiceapi` is empty in the config.");
            return;
        }
        if (voice.isListening(event.getGuild().getIdLong()))
        {
            voice.stopListening(event.getGuild());
            event.replySuccess("Stopped listening.");
            voice.speak(event.getGuild(), "Okay, I've stopped listening.");
            return;
        }
        voice.startListening(event.getGuild(), event.getChannel());
        String prefix = event.getClient().getPrefix();
        String how;
        if (voice.hasRouter())
            how = "Just talk: I'll work out when you're talking to me, and saying *Gunnabot*, *bot*, *robot* or "
                    + "*clanker* always gets my attention. Ask for music however you like, e.g. *put on some Six60* or "
                    + "*chuck on RNZ*.";
        else
            how = "I only respond when you call me *Gunnabot*, *bot*, *robot* or *clanker*. "
                    + (voice.getLlmModel() == null
                    ? "Try *bot, play Fleetwood Mac*, *bot, skip*, *pause*, *resume* or *stop*."
                    : "Ask me anything, or ask for music however you like, e.g. *robot, put on some Six60*.");
        event.replySuccess("🎙️ Listening in " + event.getMember().getVoiceState().getChannel().getAsMention()
                + ". " + how + " `" + prefix + name + "` again to stop.");
        voice.speak(event.getGuild(), "Gunnabot here. Say my name if you need me.");
    }
}
