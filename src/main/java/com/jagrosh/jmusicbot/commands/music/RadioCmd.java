package com.jagrosh.jmusicbot.commands.music;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import net.dv8tion.jda.api.entities.Message;

public class RadioCmd extends MusicCommand
{    
    private final String loadingEmoji;
    
    public RadioCmd(Bot bot)
    {
        super(bot);
        this.loadingEmoji = bot.getConfig().getLoading();
        this.name = "radio";
        this.arguments = "<radio station>";
        this.help = "streams an NZ radio station";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;
    }

    @Override
    public void doCommand(CommandEvent event) 
    {
        if(event.getArgs().isEmpty() && event.getMessage().getAttachments().isEmpty())
        {
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            if(handler.getPlayer().getPlayingTrack()!=null && handler.getPlayer().isPaused())
            {
                if(DJCommand.checkDJPermission(event))
                {
                    handler.getPlayer().setPaused(false);
                    event.replySuccess("Resumed **"+handler.getPlayer().getPlayingTrack().getInfo().title+"**.");
                }
                else
                    event.replyError("Only DJs can unpause the player!");
                return;
            }
            StringBuilder builder = new StringBuilder("\n"+event.getClient().getWarning()+" Radio Station Commands:\n");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" Hauraki`");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" GeorgeFM`");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" TheEdge`");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" MoreFM`");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" TheRock`");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" Magic`");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" CoastFM`");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" TheBreeze`");
            builder.append("\n`").append(event.getClient().getPrefix()).append(name).append(" NewstalkZB`");
            event.reply(builder.toString());
            return;
        }

        String args = event.getArgs();        
        event.reply(loadingEmoji+" Loading... `["+args+"]`", m -> bot.getStreamingPlayerManager().loadItemOrdered(event.getGuild(), args, new ResultHandler(m,event,false)));
    }
    
    private class ResultHandler implements AudioLoadResultHandler
    {
        private final Message m;
        private final CommandEvent event;
        
        private ResultHandler(Message m, CommandEvent event, boolean ytsearch)
        {
            this.m = m;
            this.event = event;
        }
        
        @Override
        public void trackLoaded(AudioTrack track)
        {
            AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
            int pos = handler.addTrack(new QueuedTrack(track, event.getAuthor()))+1;
            String addMsg = FormatUtil.filter(":radio: Playing **"+track.getInfo().title.toUpperCase()
                    +"** (`"+FormatUtil.formatTime(track.getDuration())+"`) "+(pos==0?"to begin playing":" to the queue at position "+pos));
            m.editMessage(addMsg).queue();
        }

        @Override
        public void noMatches()
        {
        	 event.reply("No Matches");
        }

        @Override
        public void loadFailed(FriendlyException throwable)
        {
            if(throwable.severity==Severity.COMMON)
                m.editMessage(event.getClient().getError()+" Error loading: "+throwable.getMessage()).queue();
            else
                m.editMessage(event.getClient().getError()+" Error loading track.").queue();
        }

		@Override
		public void playlistLoaded(AudioPlaylist playlist) {
			event.reply("WTF? WHY IS IT HERE");
		}
    }
}
