/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.message.MessageDeleteEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author John Grosh (john.a.grosh@gmail.com)
 */
public class Listener extends ListenerAdapter
{
    private static final long GREET_COOLDOWN_MS = 60_000;
    private final Bot bot;
    private final Map<Long, Long> greeted = new ConcurrentHashMap<>();
    
    public Listener(Bot bot)
    {
        this.bot = bot;
    }
    
    @Override
    public void onReady(ReadyEvent event) 
    {
        if(event.getJDA().getGuildCache().isEmpty())
        {
            Logger log = LoggerFactory.getLogger("MusicBot");
            log.warn("This bot is not on any guilds! Use the following link to add the bot to your guilds!");
            log.warn(event.getJDA().getInviteUrl(JMusicBot.RECOMMENDED_PERMS));
        }
        credit(event.getJDA());
        // back from a self-edit deploy: rejoin the voice channel and say so
        bot.getVoiceManager().resumeAfterRestart(event.getJDA());
        event.getJDA().getGuilds().forEach((guild) -> 
        {
            try
            {
                String defpl = bot.getSettingsManager().getSettings(guild).getDefaultPlaylist();
                VoiceChannel vc = bot.getSettingsManager().getSettings(guild).getVoiceChannel(guild);
                if(defpl!=null && vc!=null && bot.getPlayerManager().setUpHandler(guild).playFromDefault())
                {
                    guild.getAudioManager().openAudioConnection(vc);
                }
            }
            catch(Exception ignore) {}
        });
    }
    
    @Override
    public void onMessageDelete(MessageDeleteEvent event)
    {
        // Also fires for DMs, where getGuild() throws
        if (!event.isFromGuild())
            return;
        bot.getNowplayingHandler().onMessageDelete(event.getGuild(), event.getMessageIdLong());
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event)
    {
        bot.getAloneInVoiceHandler().onVoiceUpdate(event);
        greet(event);
        if (!event.getMember().getUser().isBot())
            bot.getLastSeen().voice(event.getMember().getIdLong(), event.getMember().getEffectiveName(),
                    System.currentTimeMillis());
    }

    /** Greets someone who joined the channel the bot is in, if the server has ?greet on. */
    private void greet(GuildVoiceUpdateEvent event)
    {
        AudioChannelUnion joined = event.getChannelJoined();
        Member member = event.getMember();
        Guild guild = event.getGuild();
        if (joined == null || member.getUser().isBot() || !bot.getVoiceManager().isEnabled()
                || !bot.getSettingsManager().getSettings(guild).isGreet())
            return;
        AudioChannel here = guild.getAudioManager().getConnectedChannel();
        if (here == null || here.getIdLong() != joined.getIdLong())
            return;
        // someone bouncing in and out shouldn't be greeted every time
        long now = System.currentTimeMillis();
        Long before = greeted.get(member.getIdLong());
        if (before != null && now - before < GREET_COOLDOWN_MS)
            return;
        greeted.put(member.getIdLong(), now);
        bot.getVoiceManager().greet(guild, member.getIdLong(), member.getEffectiveName());
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event)
    {
        if (event.isFromGuild() && !event.getAuthor().isBot() && !event.isWebhookMessage() && event.getMember() != null)
            bot.getLastSeen().message(event.getAuthor().getIdLong(), event.getMember().getEffectiveName(),
                    System.currentTimeMillis());
    }

    @Override
    public void onShutdown(ShutdownEvent event) 
    {
        bot.shutdown();
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) 
    {
        credit(event.getJDA());
    }
    
    // make sure people aren't adding clones to dbots
    private void credit(JDA jda)
    {
        Guild dbots = jda.getGuildById(110373943822540800L);
        if(dbots==null)
            return;
        if(bot.getConfig().getDBots())
            return;
        jda.getTextChannelById(119222314964353025L)
                .sendMessage("This account is running JMusicBot. Please do not list bot clones on this server, <@"+bot.getConfig().getOwnerId()+">.").complete();
        dbots.leave().queue();
    }
}
