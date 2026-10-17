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
 * Toggles voice chat: the bot listens in its voice channel and answers out
 * loud, runs playback commands, and looks things up to come back to later.
 */
public class ChatCmd extends MusicCommand
{
    public ChatCmd(Bot bot)
    {
        super(bot);
        this.name = "chat";
        this.help = "talk with me in voice: I listen, answer out loud, look things up and take music requests (run again to stop)";
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
                    + "*chuck on RNZ*. Ask me to look something up and I'll come back with it.";
        else
            how = "I only respond when you call me *Gunnabot*, *bot*, *robot* or *clanker*. "
                    + (voice.getLlmModel() == null
                    ? "Try *bot, play Fleetwood Mac*, *bot, skip*, *pause*, *resume* or *stop*."
                    : "Ask me anything, or ask for music however you like, e.g. *robot, put on some Six60*.");
        if (event.getMember().getVoiceState().getChannel() instanceof net.dv8tion.jda.api.entities.channel.attribute.IAgeRestrictedChannel c
                && c.isNSFW())
            how += " This channel is 18+: `" + prefix + "explicit on` (or just ask me) turns my explicit side on.";
        event.replySuccess("🎙️ Listening in " + event.getMember().getVoiceState().getChannel().getAsMention()
                + ". " + how + " `" + prefix + name + "` again to stop listening; I'll stay in the channel until you tell me "
                + "to leave (or `" + prefix + "leave`).");
        voice.speak(event.getGuild(), "Gunnabot's here. What are we listening to?");
    }
}
