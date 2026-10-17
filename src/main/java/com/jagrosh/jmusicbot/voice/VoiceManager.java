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
package com.jagrosh.jmusicbot.voice;

import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voice features: ?say (text-to-speech) and ?interact, which listens to the
 * voice channel, transcribes each utterance, posts it, and either runs it as
 * a playback command or passes it to the LLM and speaks the reply. Speech
 * runs on an external OpenAI-compatible server (see {@link VoiceApi}) and the
 * LLM on OpenRouter (see {@link LlmClient}). With several people in the
 * channel the bot only answers when addressed ("gunna, ...") so it doesn't
 * reply to every bit of chatter.
 */
public class VoiceManager
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceManager.class);

    private static class Session
    {
        final VoiceListener listener;
        final MessageChannel channel;
        final ScheduledFuture<?> poller;
        final LlmClient.Conversation conversation;

        Session(VoiceListener listener, MessageChannel channel, ScheduledFuture<?> poller, LlmClient.Conversation conversation)
        {
            this.listener = listener;
            this.channel = channel;
            this.poller = poller;
            this.conversation = conversation;
        }
    }

    private final Bot bot;
    private final VoiceApi api;
    private final LlmClient llm;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "voice-ticker"));
    // speech requests are slow-ish network calls; keep them off JDA's threads
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> daemon(r, "voice-worker"));

    public VoiceManager(Bot bot, BotConfig config)
    {
        this.bot = bot;
        String url = config.getVoiceApi();
        this.api = url == null || url.isBlank() ? null
                : new VoiceApi(url, config.getVoiceSttModel(), config.getVoiceTtsModel(), config.getVoiceTtsVoice());
        String key = config.getLlmKey();
        this.llm = key == null || key.isBlank() ? null
                : new LlmClient(config.getLlmApi(), key, config.getLlmModel(), config.getLlmPrompt(), config.getLlmMaxTokens());
    }

    /** The LLM model ?interact talks to, or null when there's no LLM configured. */
    public String getLlmModel()
    {
        return llm == null ? null : llm.getModel();
    }

    public boolean isEnabled()
    {
        return api != null;
    }

    public boolean isListening(long guildId)
    {
        return sessions.containsKey(guildId);
    }

    public void startListening(Guild guild, MessageChannel channel)
    {
        stopListening(guild);
        long guildId = guild.getIdLong();
        VoiceListener listener = new VoiceListener((user, wav) -> workers.submit(() -> onUtterance(guildId, user, wav)));
        ScheduledFuture<?> poller = ticker.scheduleAtFixedRate(listener::flushIdle, 100, 100, TimeUnit.MILLISECONDS);
        sessions.put(guildId, new Session(listener, channel, poller, llm == null ? null : llm.newConversation()));
        guild.getAudioManager().setReceivingHandler(listener);
    }

    public void stopListening(Guild guild)
    {
        Session s = sessions.remove(guild.getIdLong());
        if (s == null)
            return;
        s.poller.cancel(false);
        guild.getAudioManager().setReceivingHandler(null);
    }

    /** Speaks text in the guild's voice channel; the bot must already be connected. */
    public void speak(Guild guild, String text)
    {
        if (api == null || text.isBlank())
            return;
        long guildId = guild.getIdLong();
        workers.submit(() ->
        {
            try
            {
                byte[] wav = api.speak(text);
                Path file = Files.createTempFile("gunna-tts-", ".wav");
                Files.write(file, wav);
                Guild g = bot.getJDA().getGuildById(guildId);
                if (g == null)
                {
                    Files.deleteIfExists(file);
                    return;
                }
                AudioHandler handler = bot.getPlayerManager().setUpHandler(g);
                bot.getPlayerManager().loadItemOrdered(handler.getSpeaker(), file.toString(), new AudioLoadResultHandler()
                {
                    @Override
                    public void trackLoaded(AudioTrack track)
                    {
                        track.setUserData(file);
                        handler.getSpeaker().enqueue(track);
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) { discard(); }

                    @Override
                    public void noMatches() { discard(); }

                    @Override
                    public void loadFailed(FriendlyException e)
                    {
                        LOG.warn("Could not load TTS clip", e);
                        discard();
                    }

                    private void discard()
                    {
                        try { Files.deleteIfExists(file); } catch (Exception ignored) {}
                    }
                });
            }
            catch (Exception e)
            {
                LOG.warn("TTS request failed", e);
            }
        });
    }

    private void onUtterance(long guildId, User user, byte[] wav)
    {
        Session session = sessions.get(guildId);
        Guild guild = bot.getJDA().getGuildById(guildId);
        if (session == null || guild == null)
            return;
        String text;
        try
        {
            text = api.transcribe(wav);
        }
        catch (Exception e)
        {
            LOG.warn("STT request failed", e);
            return;
        }
        if (text.isBlank())
            return;

        Member member = guild.getMember(user);
        String name = member != null ? member.getEffectiveName() : user.getName();
        session.channel.sendMessage(FormatUtil.filter("🎙️ **" + name + ":** " + text)).queue(); // 🎙️

        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        VoiceCommand cmd = VoiceCommand.parse(text);
        switch (cmd.type)
        {
            case PLAY:
                play(guild, handler, user, cmd.arg, session.channel);
                break;
            case SKIP:
                if (handler.getPlayer().getPlayingTrack() == null)
                    speak(guild, "Nothing's playing.");
                else
                {
                    handler.getPlayer().stopTrack();
                    speak(guild, "Skipped.");
                }
                break;
            case PAUSE:
                handler.getPlayer().setPaused(true);
                speak(guild, "Paused.");
                break;
            case RESUME:
                handler.getPlayer().setPaused(false);
                speak(guild, "Resuming.");
                break;
            case STOP:
                handler.stopAndClear();
                speak(guild, "Stopped, and cleared the queue.");
                break;
            case NOW_PLAYING:
                AudioTrack t = handler.getPlayer().getPlayingTrack();
                speak(guild, t == null ? "Nothing's playing." : "This is " + t.getInfo().title + ".");
                break;
            default:
                chat(guild, session, name, cmd);
        }
    }

    private void chat(Guild guild, Session session, String name, VoiceCommand cmd)
    {
        if (session.conversation == null)
        {
            speak(guild, "You said: " + cmd.arg);
            return;
        }
        // in a group, only answer when spoken to
        if (!cmd.addressed && humansInChannel(guild) > 1)
            return;
        try
        {
            String reply = session.conversation.reply(name, cmd.arg);
            if (reply.isEmpty())
                return;
            session.channel.sendMessage(FormatUtil.filter("\uD83E\uDD16 **" + guild.getSelfMember().getEffectiveName() + ":** " + reply)).queue(); // 🤖
            speak(guild, LlmClient.forSpeech(reply));
        }
        catch (Exception e)
        {
            LOG.warn("LLM request failed", e);
            speak(guild, "Sorry, my brain isn't responding right now.");
        }
    }

    private static long humansInChannel(Guild guild)
    {
        GuildVoiceState vs = guild.getSelfMember().getVoiceState();
        if (vs == null || vs.getChannel() == null)
            return 0;
        return vs.getChannel().getMembers().stream().filter(m -> !m.getUser().isBot()).count();
    }

    private void play(Guild guild, AudioHandler handler, User user, String query, MessageChannel channel)
    {
        bot.getPlayerManager().loadItemOrdered(guild, "ytsearch:" + query, new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack track)
            {
                queue(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist)
            {
                AudioTrack track = playlist.getSelectedTrack() != null ? playlist.getSelectedTrack()
                        : playlist.getTracks().isEmpty() ? null : playlist.getTracks().get(0);
                if (track == null)
                    noMatches();
                else
                    queue(track);
            }

            @Override
            public void noMatches()
            {
                speak(guild, "I couldn't find " + query + ".");
            }

            @Override
            public void loadFailed(FriendlyException e)
            {
                speak(guild, "Sorry, that didn't load.");
            }

            private void queue(AudioTrack track)
            {
                if (bot.getConfig().isTooLong(track))
                {
                    speak(guild, "That one's too long.");
                    return;
                }
                RequestMetadata rm = new RequestMetadata(user, new RequestMetadata.RequestInfo(query, track.getInfo().uri));
                int pos = handler.addTrack(new QueuedTrack(track, rm)) + 1;
                String title = track.getInfo().title;
                channel.sendMessage(FormatUtil.filter(bot.getConfig().getSuccess() + " Added **" + title + "** "
                        + (pos == 0 ? "to begin playing" : "to the queue at position " + pos))).queue();
                speak(guild, pos == 0 ? "Playing " + title + "." : "Queued " + title + ".");
            }
        });
    }

    private static Thread daemon(Runnable r, String name)
    {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
