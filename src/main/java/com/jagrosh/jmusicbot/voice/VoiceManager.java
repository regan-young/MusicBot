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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
    // a line from the person the bot just answered, this soon after, is likely a reply to it
    private static final long FOLLOW_UP_MS = 15_000;
    // a line that waited longer than this to be processed is stale; answering
    // it late is worse than not answering
    private static final long STALE_MS = 10_000;
    // after just the bot's name, how long to wait for the rest before saying "Yes?"
    private static final long NAME_WAIT_MS = 1500;

    private static class Line
    {
        final long time = System.currentTimeMillis();
        final String text;

        Line(String speaker, String text)
        {
            this.text = speaker + ": " + text;
        }
    }

    /**
     * Something a speaker said to the bot that it hasn't answered yet: just
     * its name, or a chat line waiting on the LLM. If they say more first,
     * the new line joins this one and the pending answer is cancelled.
     */
    private static class Turn
    {
        final String text;
        final boolean named;
        final Line line;
        volatile Future<?> pending; // the LLM call, or the "Yes?" timer after a bare name

        Turn(String text, boolean named, Line line)
        {
            this.text = text;
            this.named = named;
            this.line = line;
        }
    }

    private static class Session
    {
        final MessageChannel channel;
        final VoiceListener listener;
        final ScheduledFuture<?> poller;
        final LlmClient.Conversation conversation;
        private final Deque<Line> log = new ArrayDeque<>();
        // each speaker's lines are handled one after another, in order
        final Map<Long, CompletableFuture<Void>> lines = new ConcurrentHashMap<>();
        final Map<Long, Turn> turns = new ConcurrentHashMap<>();
        private volatile long lastSpoke;
        private volatile long lastAnswered; // user id of the last person the bot responded to

        Session(MessageChannel channel, VoiceListener listener, ScheduledFuture<?> poller, LlmClient.Conversation conversation)
        {
            this.channel = channel;
            this.listener = listener;
            this.poller = poller;
            this.conversation = conversation;
        }

        synchronized Line add(String speaker, String text)
        {
            return replace(null, speaker, text);
        }

        /** Swaps a line for a longer version of it, moved to the end of the log. */
        synchronized Line replace(Line old, String speaker, String text)
        {
            if (old != null)
                log.removeIf(l -> l == old);
            Line line = new Line(speaker, text);
            log.add(line);
            long cutoff = System.currentTimeMillis() - LOG_MAX_AGE_MS;
            while (log.size() > LOG_LINES || (!log.isEmpty() && log.peekFirst().time < cutoff))
                log.removeFirst();
            return line;
        }

        synchronized List<String> recent()
        {
            List<String> lines = new ArrayList<>();
            log.forEach(l -> lines.add(l.text));
            return lines;
        }

        /** Whether the bot just answered this person, so their next line is probably a reply. */
        boolean isFollowUp(User user)
        {
            return user.getIdLong() == lastAnswered && System.currentTimeMillis() - lastSpoke < FOLLOW_UP_MS;
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
    // speech has its own thread, so replies never wait behind transcriptions
    // and clips are synthesised in the order they were asked for
    private final ExecutorService speech = Executors.newSingleThreadExecutor(r -> daemon(r, "voice-tts"));
    // LLM calls run beside the router, so they get their own threads
    private final ExecutorService llmCalls = Executors.newCachedThreadPool(r -> daemon(r, "voice-llm"));
    // bumped by "hush" so queued speech is dropped
    private final Map<Long, AtomicInteger> speechEpochs = new ConcurrentHashMap<>();
    private final SmartTurn smartTurn;
    // end-of-turn checks take ~30 ms of CPU each; one at a time is plenty
    private final ExecutorService turnChecks = Executors.newSingleThreadExecutor(r -> daemon(r, "voice-turn"));

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
        // the router can share the chat LLM's key (both OpenRouter, say)
        String routerKey = config.getRouterKey() == null || config.getRouterKey().isBlank() ? key : config.getRouterKey();
        this.router = routerUrl == null || routerUrl.isBlank() ? null
                : new VoiceRouter(routerUrl, routerKey, config.getRouterModel());
        this.smartTurn = loadSmartTurn(config.getSmartTurnModel());
    }

    private static SmartTurn loadSmartTurn(String model)
    {
        if (model == null || model.isBlank())
            return null;
        Path path = Path.of(model);
        if (!Files.exists(path))
        {
            LOG.info("No Smart Turn model at {}; utterances end after {} ms of silence", path, VoiceListener.END_SILENCE_MS);
            return null;
        }
        try
        {
            SmartTurn st = new SmartTurn(path);
            LOG.info("Smart Turn end-of-turn model loaded from {}", path);
            return st;
        }
        catch (Exception | LinkageError e)
        {
            // LinkageError: ONNX Runtime's native library didn't load
            LOG.warn("Could not load Smart Turn; utterances end after a fixed silence", e);
            return null;
        }
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
        VoiceListener listener = new VoiceListener((user, wav, heardAt, ending) ->
        {
            Session s = sessions.get(guildId);
            if (s == null)
                return;
            // one speaker's lines run in order, so a line can see the turn the one before it opened
            s.lines.compute(user.getIdLong(), (id, before) ->
                    (before == null ? CompletableFuture.<Void>completedFuture(null) : before).thenRunAsync(() ->
                    {
                        try
                        {
                            onUtterance(guildId, user, wav, heardAt, ending);
                        }
                        catch (Exception e)
                        {
                            LOG.warn("Voice line failed", e);
                        }
                    }, workers));
        }, smartTurn, turnChecks);
        ScheduledFuture<?> poller = ticker.scheduleAtFixedRate(listener::flushIdle, 100, 100, TimeUnit.MILLISECONDS);
        sessions.put(guildId, new Session(channel, listener, poller, llm == null ? null : llm.newConversation()));
        guild.getAudioManager().setReceivingHandler(listener);
    }

    public void stopListening(Guild guild)
    {
        Session s = sessions.remove(guild.getIdLong());
        if (s == null)
            return;
        s.poller.cancel(false);
        s.turns.values().forEach(t -> t.pending.cancel(true));
        guild.getAudioManager().setReceivingHandler(null);
    }

    /** Speaks text in the guild's voice channel; the bot must already be connected. */
    public void speak(Guild guild, String text)
    {
        speak(guild, text, 0);
    }

    /**
     * Speaks text one sentence at a time, so the first sentence plays while
     * the rest are synthesised.
     * @param heardAt when the speaker being answered stopped talking, for the latency log, or 0
     */
    private void speak(Guild guild, String text, long heardAt)
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
        int asked = epoch(guildId).get();
        List<String> parts = sentences(text);
        for (int i = 0; i < parts.size(); i++)
            say(guildId, parts.get(i), i == 0 ? heardAt : 0, asked);
    }

    /**
     * Queues one sentence to be synthesised and played.
     * @param asked the speech epoch when it was asked for; if the bot's been hushed since, it's dropped
     */
    private void say(long guildId, String part, long heardAt, int asked)
    {
        AtomicInteger epoch = epoch(guildId);
        speech.submit(() ->
        {
            if (epoch.get() != asked)
                return; // hushed
            try
            {
                long start = System.currentTimeMillis();
                byte[] wav = api.speak(part);
                long now = System.currentTimeMillis();
                if (heardAt > 0)
                    LOG.info("Spoke in {} ms, {} ms after they stopped talking: {}", now - start, now - heardAt, part);
                else
                    LOG.info("Spoke in {} ms: {}", now - start, part);
                play(guildId, wav, epoch, asked);
            }
            catch (Exception e)
            {
                LOG.warn("TTS request failed", e);
            }
        });
    }

    private AtomicInteger epoch(long guildId)
    {
        return speechEpochs.computeIfAbsent(guildId, id -> new AtomicInteger());
    }

    private void play(long guildId, byte[] wav, AtomicInteger epoch, int asked) throws java.io.IOException
    {
        Guild g = bot.getJDA().getGuildById(guildId);
        if (g == null || epoch.get() != asked)
            return;
        Path file = Files.createTempFile("gunna-tts-", ".wav");
        Files.write(file, wav);
        AudioHandler handler = bot.getPlayerManager().setUpHandler(g);
        bot.getPlayerManager().loadItemOrdered(handler.getSpeaker(), file.toString(), new AudioLoadResultHandler()
        {
            @Override
            public void trackLoaded(AudioTrack track)
            {
                track.setUserData(file);
                if (epoch.get() == asked)
                    handler.getSpeaker().enqueue(track);
                else
                    discard();
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

    /** Stops the bot talking: drops queued speech and cuts off the current clip. Music carries on. */
    private void hush(Guild guild)
    {
        epoch(guild.getIdLong()).incrementAndGet();
        bot.getPlayerManager().setUpHandler(guild).getSpeaker().clear();
    }

    // a sentence shorter than this joins the next, so clips aren't choppy
    private static final int MIN_SENTENCE = 24;

    /** Splits a reply into sentences for speaking one by one. */
    static List<String> sentences(String text)
    {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        // don't split after titles like "Mr." in "Mr. Brightside"
        for (String s : text.trim().split("(?<!\\b(?:Mr|Mrs|Ms|Dr|St|vs))(?<=[.!?])\\s+"))
        {
            if (cur.length() > 0)
                cur.append(' ');
            cur.append(s);
            if (cur.length() >= MIN_SENTENCE)
            {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0)
            out.add(cur.toString());
        return out;
    }

    /**
     * @param heardAt when they stopped talking
     * @param ending how the end of the utterance was decided, for the logs
     */
    private void onUtterance(long guildId, User user, byte[] wav, long heardAt, String ending)
    {
        Session session = sessions.get(guildId);
        Guild guild = bot.getJDA().getGuildById(guildId);
        if (session == null || guild == null)
            return;
        long waited = System.currentTimeMillis() - heardAt;
        if (waited > STALE_MS)
        {
            LOG.warn("Dropping a line from {} that waited {} ms; the speech server is falling behind", user.getName(), waited);
            return;
        }
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
        long uid = user.getIdLong();

        Member member = guild.getMember(user);
        String name = member != null ? member.getEffectiveName() : user.getName();
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        boolean followUp = session.isFollowUp(user);
        boolean named = VoiceCommand.parse(text).addressed;

        // They kept talking before the bot answered ("Gunnabot, um..." then
        // the question, or a question in two breaths): it's all one turn, and
        // the answer to the first half is cancelled.
        Turn before = session.turns.remove(uid);
        Line line;
        if (before != null)
        {
            before.pending.cancel(true);
            text = before.text + " " + text;
            named |= before.named;
            line = session.replace(before.line, name, text);
        }
        else
            line = session.add(name, text);
        String said = text;
        String how = (before != null ? " continued" : "") + (named ? "" : followUp ? " follow-up" : " unnamed");
        String heard = audioMs + " ms audio, " + ending + ", STT " + sttMs + " ms";

        if (VoiceCommand.isNameOnly(said))
        {
            Turn turn = new Turn(said, true, line);
            session.turns.put(uid, turn);
            turn.pending = ticker.schedule(() -> answerName(guild, session, user, turn), NAME_WAIT_MS, TimeUnit.MILLISECONDS);
            LOG.info("Heard {} ({}){}: {} -> waiting for the rest", name, heard, how, said);
            return;
        }

        List<String> recent = session.recent();
        String player = playerState(handler);

        // When the bot is named, or is being answered, it's almost certainly
        // being spoken to, so the chat LLM starts alongside the router and a
        // chat reply isn't delayed by it. It's cancelled if the router picks a
        // command instead.
        // Its sentences are held until the router agrees it's chat.
        SpeechGate gate = new SpeechGate();
        CompletableFuture<LlmClient.Decision> chat = named || followUp ? askLlm(session, name, said, player, recent, gate) : null;

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
        // named but the router saw no command: treat it as talk
        String command = !toBot ? "none" : route == null || !route.toBot ? "chat" : route.command;
        if (chat != null && !command.equals("chat"))
        {
            gate.close();
            chat.cancel(true);
        }
        if (!toBot)
        {
            LOG.info("Heard {} ({}, router {} ms), not for the bot: {}", name, heard, routerMs, said);
            return;
        }
        session.lastAnswered = uid;
        String args = route == null ? "" : route.args;
        LOG.info("Heard {} ({}, router {} ms){}: {} -> {} [{}]", name, heard, routerMs, how, said, command, args);

        if (!command.equals("chat"))
        {
            run(guild, member, session, command, args, heardAt);
            return;
        }
        if (session.conversation == null)
        {
            // no chat LLM: fall back to the simple keyword commands
            VoiceCommand parsed = VoiceCommand.parse(said);
            runDecision(guild, member, session, parsed.type, parsed.arg, "", heardAt);
            return;
        }
        Turn turn = new Turn(said, named, line);
        CompletableFuture<LlmClient.Decision> reply = chat != null ? chat : askLlm(session, name, said, player, recent, gate);
        turn.pending = reply;
        session.turns.put(uid, turn);
        long asked = System.currentTimeMillis();
        int epochAsked = epoch(guildId).get();
        AtomicInteger spoken = new AtomicInteger();
        AtomicBoolean dropped = new AtomicBoolean();
        // the reply streams in a sentence at a time, and each is spoken as it arrives
        gate.open(part ->
        {
            if (dropped.get())
                return;
            // the first sentence closes the turn: once the bot is talking,
            // more from them is a new line, not part of this one
            if (spoken.get() == 0)
            {
                if (!session.turns.remove(uid, turn))
                {
                    dropped.set(true); // they kept talking; the longer turn gets the answer
                    return;
                }
                LOG.info("LLM first sentence in {} ms", System.currentTimeMillis() - asked);
            }
            session.lastSpoke = System.currentTimeMillis();
            say(guildId, part, spoken.getAndIncrement() == 0 ? heardAt : 0, epochAsked);
        });
        reply.whenCompleteAsync((d, e) ->
        {
            boolean streamed = spoken.get() > 0 && !dropped.get();
            // superseded before it spoke: they said more, and the longer turn gets the answer
            if (!streamed && !session.turns.remove(uid, turn))
                return;
            if (e != null)
            {
                LOG.warn("LLM request failed", e);
                if (!streamed)
                    speak(guild, "Sorry, my brain isn't responding right now.");
                return;
            }
            LOG.info("LLM {} ms: {} [{}] {}", System.currentTimeMillis() - asked, d.action, d.query, d.reply);
            if (streamed)
            {
                // already spoken; just remember it for the router and follow-ups
                session.add(BOT_NAME, LlmClient.forSpeech(d.reply));
                session.lastSpoke = System.currentTimeMillis();
                return;
            }
            runDecision(guild, member, session, d.action, d.query, LlmClient.forSpeech(d.reply), heardAt);
        }, llmCalls);
    }

    /**
     * Holds a streamed reply's sentences until it's known the line was chat
     * (the LLM can start before the router has decided), then passes them on.
     */
    private static class SpeechGate implements Consumer<String>
    {
        private final List<String> held = new ArrayList<>();
        private Consumer<String> sink;
        private boolean closed;

        @Override
        public synchronized void accept(String sentence)
        {
            if (closed)
                return;
            if (sink == null)
                held.add(sentence);
            else
                sink.accept(sentence);
        }

        synchronized void open(Consumer<String> sink)
        {
            this.sink = sink;
            held.forEach(sink);
            held.clear();
        }

        synchronized void close()
        {
            closed = true;
            held.clear();
        }
    }

    /** Just the bot's name and then nothing: answer it, unless they've started saying more. */
    private void answerName(Guild guild, Session session, User user, Turn turn)
    {
        if (session.listener.isSpeaking(user) && session.turns.get(user.getIdLong()) == turn)
        {
            // still talking; the rest will join this turn
            turn.pending = ticker.schedule(() -> answerName(guild, session, user, turn), 300, TimeUnit.MILLISECONDS);
            return;
        }
        if (!session.turns.remove(user.getIdLong(), turn))
            return;
        session.lastAnswered = user.getIdLong();
        speak(guild, "Yes?");
    }

    private CompletableFuture<LlmClient.Decision> askLlm(Session session, String name, String text, String player, List<String> recent,
            Consumer<String> onSentence)
    {
        if (session.conversation == null)
            return null;
        // the last line is the one being answered; the rest is context
        List<String> before = recent.subList(0, Math.max(0, recent.size() - 1));
        String context = "Player: " + player
                + (before.isEmpty() ? "" : " Recent conversation in the channel:\n" + String.join("\n", before));
        return session.conversation.decide(name, text, context, onSentence);
    }

    /** Acts on what the chat LLM (or the keyword fallback) decided. */
    private void runDecision(Guild guild, Member member, Session session, VoiceCommand.Type action, String query, String reply,
            long heardAt)
    {
        switch (action)
        {
            case PLAY:   run(guild, member, session, "play", query, heardAt); break;
            case RADIO:  run(guild, member, session, "radio", query, heardAt); break;
            case MIX:    run(guild, member, session, "mix", query, heardAt); break;
            case SKIP:   run(guild, member, session, "skip", "", heardAt); break;
            case STOP:   run(guild, member, session, "stop", "", heardAt); break;
            case PAUSE:  run(guild, member, session, "pause", "", heardAt); break;
            case RESUME: run(guild, member, session, "resume", "", heardAt); break;
            case NOW_PLAYING:
                AudioTrack t = bot.getPlayerManager().setUpHandler(guild).getPlayer().getPlayingTrack();
                speak(guild, t == null ? "Nothing's playing." : "This is " + t.getInfo().title + ".", heardAt);
                break;
            default:
                speak(guild, reply, heardAt);
        }
    }

    /**
     * Runs a playback command. Pause and resume act on the player directly
     * (?pause is a toggle); the rest run the real command as the speaker, and
     * the bot says a short confirmation, or the command's error if it fails.
     */
    private void run(Guild guild, Member member, Session session, String command, String args, long heardAt)
    {
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        switch (command)
        {
            case "pause":
                handler.getPlayer().setPaused(true);
                speak(guild, "Paused.", heardAt);
                return;
            case "resume":
                handler.getPlayer().setPaused(false);
                speak(guild, "Resuming.", heardAt);
                return;
            case "hush":
                hush(guild);
                return;
            case "volume":
                if (!args.matches("\\d{1,3}"))
                {
                    speak(guild, "How loud would you like it, out of a hundred and fifty?", heardAt);
                    return;
                }
                break;
            case "play":
            case "radio":
                if (args.isBlank())
                {
                    speak(guild, command.equals("play") ? "What should I play?" : "Which station?", heardAt);
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
                speak(guild, spokenReply(reply), heardAt);
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
            speak(guild, String.format(ack, args), heardAt);
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
