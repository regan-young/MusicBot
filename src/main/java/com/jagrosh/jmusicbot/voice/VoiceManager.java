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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voice features: ?say (text-to-speech) and ?interact, which listens to the
 * voice channel, transcribes each utterance, and runs it as a playback
 * command or answers it out loud. Speech
 * runs on an external OpenAI-compatible server (see {@link VoiceApi}) and the
 * LLM on OpenRouter (see {@link LlmClient}), which decides whether each
 * utterance is a playback command or just talk and writes the spoken reply.
 * Transcripts and replies are logged rather than posted.
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
                long start = System.currentTimeMillis();
                byte[] wav = api.speak(text);
                LOG.info("Spoke in {} ms: {}", System.currentTimeMillis() - start, text);
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
        long start = System.currentTimeMillis();
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
        long sttMs = System.currentTimeMillis() - start;
        // only respond when called by name, so the bot stays out of conversations
        VoiceCommand parsed = VoiceCommand.parse(text);
        if (!parsed.addressed)
        {
            LOG.info("Heard {} ({} ms audio, STT {} ms), not addressed: {}", name,
                    VoiceAudio.durationMs(wav.length - 44), sttMs, text);
            return;
        }
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);

        // The LLM classifies the utterance and writes the reply in one call;
        // keyword matching is the fallback when there's no LLM or it fails.
        VoiceCommand.Type action;
        String query, reply;
        LlmClient.Decision d = null;
        if (session.conversation != null)
        {
            long llmStart = System.currentTimeMillis();
            try
            {
                d = session.conversation.decide(name, text, playerContext(handler));
                LOG.info("Heard {} ({} ms audio, STT {} ms, LLM {} ms): {} -> {} [{}] {}", name,
                        VoiceAudio.durationMs(wav.length - 44), sttMs, System.currentTimeMillis() - llmStart,
                        text, d.action, d.query, d.reply);
            }
            catch (Exception e)
            {
                LOG.warn("LLM request failed, falling back to keywords", e);
            }
        }
        if (d != null)
        {
            action = d.action;
            query = d.query;
            reply = LlmClient.forSpeech(d.reply);
        }
        else
        {
            LOG.info("Heard {} ({} ms audio, STT {} ms): {} -> {} (keywords)", name,
                    VoiceAudio.durationMs(wav.length - 44), sttMs, text, parsed.type);
            action = parsed.type;
            query = parsed.arg;
            reply = "";
        }

        switch (action)
        {
            case PLAY:
                // the confirmation names the actual track once it has loaded
                play(guild, handler, user, query, session.channel);
                break;
            case SKIP:
                if (handler.getPlayer().getPlayingTrack() == null)
                    speak(guild, "Nothing's playing.");
                else
                {
                    handler.getPlayer().stopTrack();
                    speak(guild, orElse(reply, "Skipped."));
                }
                break;
            case PAUSE:
                handler.getPlayer().setPaused(true);
                speak(guild, orElse(reply, "Paused."));
                break;
            case RESUME:
                handler.getPlayer().setPaused(false);
                speak(guild, orElse(reply, "Resuming."));
                break;
            case STOP:
                handler.stopAndClear();
                speak(guild, orElse(reply, "Stopped, and cleared the queue."));
                break;
            case NOW_PLAYING:
                AudioTrack t = handler.getPlayer().getPlayingTrack();
                speak(guild, t == null ? "Nothing's playing." : "This is " + t.getInfo().title + ".");
                break;
            default:
                // chat; without an LLM there's nothing to say
                speak(guild, reply);
        }
    }

    /** One line describing the player for the LLM, so it can answer "what's this song?". */
    private String playerContext(AudioHandler handler)
    {
        AudioTrack t = handler.getPlayer().getPlayingTrack();
        StringBuilder sb = new StringBuilder();
        if (t == null)
            sb.append("Nothing is playing.");
        else
        {
            String label = bot.getRadioMetadata().getLabel(t);
            sb.append(handler.getPlayer().isPaused() ? "Paused: " : "Now playing: ")
              .append(label != null ? label : t.getInfo().title);
            if (t.getInfo().author != null && !t.getInfo().author.isEmpty())
                sb.append(" (").append(t.getInfo().author).append(")");
        }
        List<QueuedTrack> queue = handler.getQueue().getList();
        if (!queue.isEmpty())
        {
            sb.append(" Up next: ");
            for (int i = 0; i < Math.min(3, queue.size()); i++)
                sb.append(i == 0 ? "" : "; ").append(queue.get(i).getTrack().getInfo().title);
            if (queue.size() > 3)
                sb.append(" and ").append(queue.size() - 3).append(" more");
            sb.append('.');
        }
        return sb.toString();
    }

    private static String orElse(String s, String fallback)
    {
        return s == null || s.isBlank() ? fallback : s;
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
