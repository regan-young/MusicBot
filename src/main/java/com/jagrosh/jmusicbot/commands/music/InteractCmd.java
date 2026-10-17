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
        String chat = voice.getLlmModel() == null ? "Anything else I'll repeat back."
                : "Anything else, I'll answer (`" + voice.getLlmModel() + "`). With others in the channel, start with *Gunna* so I know you're talking to me.";
        event.replySuccess("🎙️ Listening in " + event.getMember().getVoiceState().getChannel().getAsMention()
                + ". Music: *play Fleetwood Mac*, *skip*, *pause*, *resume*, *stop*, *what's playing*. "
                + chat + " `" + prefix + name + "` again to stop. Transcripts appear here.");
        voice.speak(event.getGuild(), "Hey, I'm listening.");
    }
}
