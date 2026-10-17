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

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.BotConfig;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voice features: ?say (text-to-speech) and ?interact, which listens to the
 * voice channel and lets people talk to the bot naturally.
 *
 * Each utterance is transcribed ({@link VoiceApi}), then a small local LLM
 * ({@link VoiceRouter}) reads the recent conversation and decides whether it
 * was said to the bot and which command it maps to. Commands run through the
 * bot's real commands (?play, ?radio, ...) via {@link VoiceCommandEvent};
 * anything else goes to the conversational LLM ({@link LlmClient}) and the
 * reply is spoken. Saying one of the bot's names always counts as talking to
 * it. Transcripts and replies are logged rather than posted.
 */
public class VoiceManager
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceManager.class);

    private static final String BOT_NAME = "Gunnabot";
    // how much of the channel conversation the router and LLM see
    private static final int LOG_LINES = 10;
    private static final long LOG_MAX_AGE_MS = 3 * 60_000;
    // a line this soon after the bot spoke is likely a reply to it
    private static final long FOLLOW_UP_MS = 20_000;

    private static class Line
    {
        final long time = System.currentTimeMillis();
        final String text;

        Line(String speaker, String text)
        {
            this.text = speaker + ": " + text;
        }
    }

    private static class Session
    {
        final MessageChannel channel;
        final ScheduledFuture<?> poller;
        final LlmClient.Conversation conversation;
        private final Deque<Line> log = new ArrayDeque<>();
        private volatile long lastSpoke;

        Session(MessageChannel channel, ScheduledFuture<?> poller, LlmClient.Conversation conversation)
        {
            this.channel = channel;
            this.poller = poller;
            this.conversation = conversation;
        }

        synchronized void add(String speaker, String text)
        {
            log.add(new Line(speaker, text));
            long cutoff = System.currentTimeMillis() - LOG_MAX_AGE_MS;
            while (log.size() > LOG_LINES || (!log.isEmpty() && log.peekFirst().time < cutoff))
                log.removeFirst();
        }

        synchronized List<String> recent()
        {
            List<String> lines = new ArrayList<>();
            log.forEach(l -> lines.add(l.text));
            return lines;
        }

        boolean botJustSpoke()
        {
            return System.currentTimeMillis() - lastSpoke < FOLLOW_UP_MS;
        }
    }

    private final Bot bot;
    private final VoiceApi api;
    private final LlmClient llm;
    private final VoiceRouter router;
    private volatile CommandClient client;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "voice-ticker"));
    // speech requests are slow-ish network calls; keep them off JDA's threads
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> daemon(r, "voice-worker"));
    // LLM calls run beside the router, so they get their own threads
    private final ExecutorService llmCalls = Executors.newCachedThreadPool(r -> daemon(r, "voice-llm"));

    public VoiceManager(Bot bot, BotConfig config)
    {
        this.bot = bot;
        String url = config.getVoiceApi();
        this.api = url == null || url.isBlank() ? null
                : new VoiceApi(url, config.getVoiceSttModel(), config.getVoiceTtsModel(), config.getVoiceTtsVoice());
        String key = config.getLlmKey();
        this.llm = key == null || key.isBlank() ? null
                : new LlmClient(config.getLlmApi(), key, config.getLlmModel(), config.getLlmPrompt(), config.getLlmMaxTokens());
        String routerUrl = config.getRouterApi();
        this.router = routerUrl == null || routerUrl.isBlank() ? null : new VoiceRouter(routerUrl, config.getRouterModel());
    }

    /** Voice commands run through the same command client as typed ones. */
    public void setCommandClient(CommandClient client)
    {
        this.client = client;
    }

    /** The LLM model ?interact talks to, or null when there's no LLM configured. */
    public String getLlmModel()
    {
        return llm == null ? null : llm.getModel();
    }

    public boolean hasRouter()
    {
        return router != null;
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
        sessions.put(guildId, new Session(channel, poller, llm == null ? null : llm.newConversation()));
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
        if (api == null || text == null || text.isBlank())
            return;
        long guildId = guild.getIdLong();
        Session session = sessions.get(guildId);
        if (session != null)
        {
            session.add(BOT_NAME, text);
            session.lastSpoke = System.currentTimeMillis();
        }
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
        long sttMs = System.currentTimeMillis() - start;
        long audioMs = VoiceAudio.durationMs(wav.length - 44);

        Member member = guild.getMember(user);
        String name = member != null ? member.getEffectiveName() : user.getName();
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        boolean followUp = session.botJustSpoke();
        session.add(name, text);
        List<String> recent = session.recent();
        String player = playerState(handler);
        VoiceCommand parsed = VoiceCommand.parse(text);
        boolean named = parsed.addressed;

        // When the bot is named it's almost certainly being spoken to, so the
        // chat LLM starts alongside the router and a chat reply isn't delayed
        // by it. Unnamed lines wait for the router, so chatter between friends
        // never leaves the network.
        CompletableFuture<LlmClient.Decision> chat = named ? askLlm(session, name, text, player, recent) : null;

        VoiceRouter.Route route = null;
        long routerMs = 0;
        if (router != null)
        {
            long t = System.currentTimeMillis();
            try
            {
                route = router.route(recent, followUp, player);
            }
            catch (Exception e)
            {
                LOG.warn("Router request failed, using the bot's name only", e);
            }
            routerMs = System.currentTimeMillis() - t;
        }

        boolean toBot = named || (route != null && route.toBot);
        if (!toBot)
        {
            LOG.info("Heard {} ({} ms audio, STT {} ms, router {} ms), not for the bot: {}", name, audioMs, sttMs, routerMs, text);
            return;
        }
        // named but the router saw no command: treat it as talk
        String command = route == null || !route.toBot ? "chat" : route.command;
        String args = route == null ? "" : route.args;
        LOG.info("Heard {} ({} ms audio, STT {} ms, router {} ms){}: {} -> {} [{}]", name, audioMs, sttMs, routerMs,
                named ? "" : (followUp ? " follow-up" : " unnamed"), text, command, args);

        if (!command.equals("chat"))
        {
            run(guild, member, session, command, args);
            return;
        }
        if (session.conversation == null)
        {
            // no chat LLM: fall back to the simple keyword commands
            runDecision(guild, member, session, parsed.type, parsed.arg, "");
            return;
        }
        try
        {
            long t = System.currentTimeMillis();
            LlmClient.Decision d = (chat != null ? chat : askLlm(session, name, text, player, recent)).get(45, TimeUnit.SECONDS);
            LOG.info("LLM {} ms: {} [{}] {}", System.currentTimeMillis() - t, d.action, d.query, d.reply);
            runDecision(guild, member, session, d.action, d.query, LlmClient.forSpeech(d.reply));
        }
        catch (Exception e)
        {
            LOG.warn("LLM request failed", e);
            speak(guild, "Sorry, my brain isn't responding right now.");
        }
    }

    private CompletableFuture<LlmClient.Decision> askLlm(Session session, String name, String text, String player, List<String> recent)
    {
        if (session.conversation == null)
            return null;
        // the last line is the one being answered; the rest is context
        List<String> before = recent.subList(0, Math.max(0, recent.size() - 1));
        String context = "Player: " + player
                + (before.isEmpty() ? "" : " Recent conversation in the channel:\n" + String.join("\n", before));
        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return session.conversation.decide(name, text, context);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }, llmCalls);
    }

    /** Acts on what the chat LLM (or the keyword fallback) decided. */
    private void runDecision(Guild guild, Member member, Session session, VoiceCommand.Type action, String query, String reply)
    {
        switch (action)
        {
            case PLAY:   run(guild, member, session, "play", query); break;
            case RADIO:  run(guild, member, session, "radio", query); break;
            case MIX:    run(guild, member, session, "mix", query); break;
            case SKIP:   run(guild, member, session, "skip", ""); break;
            case STOP:   run(guild, member, session, "stop", ""); break;
            case PAUSE:  run(guild, member, session, "pause", ""); break;
            case RESUME: run(guild, member, session, "resume", ""); break;
            case NOW_PLAYING:
                AudioTrack t = bot.getPlayerManager().setUpHandler(guild).getPlayer().getPlayingTrack();
                speak(guild, t == null ? "Nothing's playing." : "This is " + t.getInfo().title + ".");
                break;
            default:
                speak(guild, reply);
        }
    }

    /**
     * Runs a playback command. Pause and resume act on the player directly
     * (?pause is a toggle); the rest run the real command as the speaker, and
     * the bot says a short confirmation, or the command's error if it fails.
     */
    private void run(Guild guild, Member member, Session session, String command, String args)
    {
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        switch (command)
        {
            case "pause":
                handler.getPlayer().setPaused(true);
                speak(guild, "Paused.");
                return;
            case "resume":
                handler.getPlayer().setPaused(false);
                speak(guild, "Resuming.");
                return;
            case "volume":
                if (!args.matches("\\d{1,3}"))
                {
                    speak(guild, "How loud would you like it, out of a hundred and fifty?");
                    return;
                }
                break;
            case "play":
            case "radio":
                if (args.isBlank())
                {
                    speak(guild, command.equals("play") ? "What should I play?" : "Which station?");
                    return;
                }
                break;
            default:
        }
        CommandClient c = client;
        if (c == null || member == null)
            return;
        AtomicBoolean spokeError = new AtomicBoolean();
        VoiceCommandEvent event = new VoiceCommandEvent(member, session.channel, args, c, reply ->
        {
            // speak the first error or warning, cleaned up; success replies are
            // covered by the confirmation
            if ((reply.startsWith(c.getError()) || reply.startsWith(c.getWarning())) && spokeError.compareAndSet(false, true))
                speak(guild, spokenReply(reply));
        });
        String name = command;
        if (command.equals("skip") && DJCommand.checkDJPermission(event))
            name = "forceskip"; // a DJ's skip shouldn't be a vote, same as /skip
        Command cmd = find(c, name);
        if (cmd == null)
        {
            LOG.warn("Voice command {} not found", name);
            return;
        }
        String ack = VoiceRouter.ACKS.get(command);
        if (ack != null)
            speak(guild, String.format(ack, args));
        cmd.run(event);
    }

    private static Command find(CommandClient client, String name)
    {
        for (Command c : client.getCommands())
            if (c.getName().equalsIgnoreCase(name))
                return c;
        return null;
    }

    /** Turns a chat reply like "❌ No results found for `xyz`!" into something speakable. */
    static String spokenReply(String reply)
    {
        String s = LlmClient.forSpeech(reply.replaceAll("<[@#&!:a-zA-Z0-9_]+>", ""))
                .replaceAll("[\\x{2190}-\\x{2BFF}]", "")
                .trim();
        int cut = s.indexOf(". ");
        if (cut > 0 && cut < 160)
            s = s.substring(0, cut + 1);
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    /** One line describing the player for the router and LLM. */
    private String playerState(AudioHandler handler)
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
                sb.append(" by ").append(t.getInfo().author);
            sb.append('.');
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
        sb.append(" Volume ").append(handler.getVolume()).append('.');
        return sb.toString();
    }

    private static Thread daemon(Runnable r, String name)
    {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
