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
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Voice features: ?say (text-to-speech) and ?chat, which listens to the
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
    // research answers wait for a lull: nobody talking for this long...
    private static final long LULL_MS = 1200;
    // ...but no longer than this
    private static final long MAX_LULL_WAIT_MS = 30_000;
    private static final int MAX_RESEARCH = 3;
    // a line that's mostly words the bot said this recently is its own voice
    // coming back through someone's speakers and mic
    private static final long ECHO_MS = 15_000;
    private static final Path FEEDBACK = Path.of("voice-feedback.jsonl");

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

    /** The last line the bot acted on, kept for "not you, bot" feedback. */
    private record Answer(long time, String speaker, String line, String command, String args, boolean named,
            boolean followUp, List<String> conversation) {}

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
        private volatile long lastHeard;    // when anyone last said something
        private volatile long talkedOver;   // user id the bot paused its speech for, or 0
        private volatile Answer lastResponse;
        // sentences the bot has said, for spotting its own voice coming back through someone's mic
        private final Deque<Line> botSaid = new ArrayDeque<>();
        // things the bot said it would look into and hasn't answered yet
        final Set<CompletableFuture<Researcher.Result>> research = ConcurrentHashMap.newKeySet();

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

        synchronized void botSaid(String sentence)
        {
            botSaid.add(new Line(BOT_NAME, sentence));
            long cutoff = System.currentTimeMillis() - ECHO_MS;
            while (!botSaid.isEmpty() && botSaid.peekFirst().time < cutoff)
                botSaid.removeFirst();
        }

        synchronized List<String> botSaidRecently()
        {
            long cutoff = System.currentTimeMillis() - ECHO_MS;
            List<String> out = new ArrayList<>();
            botSaid.forEach(l -> {
                if (l.time >= cutoff)
                    out.add(l.text.substring(BOT_NAME.length() + 2));
            });
            return out;
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
    // for channels Discord marks age-restricted, or null
    private final LlmClient nsfwLlm;
    private final VoiceRouter router;
    private final Researcher researcher;
    private final VoiceMemory memory = new VoiceMemory(Path.of("voice-memory.json"));
    private final Reminders reminders;
    private final ZoneId zone;
    // conversations with the bot by text (@mentions), per channel
    private final Map<Long, LlmClient.Conversation> textConversations = new ConcurrentHashMap<>();
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
    private final Map<Long, AtomicInteger> speechQueued = new ConcurrentHashMap<>();
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
        String nsfwModel = config.getNsfwLlmModel();
        this.nsfwLlm = llm == null || nsfwModel == null || nsfwModel.isBlank() ? null
                : new LlmClient(config.getNsfwLlmApi(), config.getNsfwLlmKey(), nsfwModel,
                        config.getNsfwLlmPrompt().isBlank() ? config.getLlmPrompt() : config.getNsfwLlmPrompt(),
                        config.getLlmMaxTokens());
        String routerUrl = config.getRouterApi();
        // the router can share the chat LLM's key (both OpenRouter, say)
        String routerKey = config.getRouterKey() == null || config.getRouterKey().isBlank() ? key : config.getRouterKey();
        this.router = routerUrl == null || routerUrl.isBlank() ? null
                : new VoiceRouter(routerUrl, routerKey, config.getRouterModel());
        this.smartTurn = loadSmartTurn(config.getSmartTurnModel());
        // web search is an OpenRouter server tool
        this.researcher = llm == null || !config.getLlmApi().contains("openrouter.ai") ? null
                : new Researcher(config.getLlmApi(), key, config.getResearchModel(), config.getLlmPrompt());
        this.reminders = new Reminders(Path.of("voice-reminders.json"), ticker, this::remind);
        String tz = config.getTimezone();
        this.zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
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

    /** The LLM model ?chat talks to, or null when there's no LLM configured. */
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
        }, user -> onTalking(guildId, user), smartTurn, turnChecks);
        ScheduledFuture<?> poller = ticker.scheduleAtFixedRate(listener::flushIdle, 100, 100, TimeUnit.MILLISECONDS);
        var voice = guild.getSelfMember().getVoiceState();
        LlmClient brain = brainFor(voice == null ? null : voice.getChannel());
        if (brain != null && brain == nsfwLlm)
            LOG.info("?chat in an 18+ channel: using {} and the 18+ persona", brain.getModel());
        sessions.put(guildId, new Session(channel, listener, poller, brain == null ? null : brain.newConversation()));
        guild.getAudioManager().setReceivingHandler(listener);
    }

    public void stopListening(Guild guild)
    {
        Session s = sessions.remove(guild.getIdLong());
        if (s == null)
            return;
        s.poller.cancel(false);
        s.turns.values().forEach(t -> t.pending.cancel(true));
        s.research.forEach(r -> r.cancel(true));
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
        AtomicInteger queued = queued(guildId);
        queued.incrementAndGet();
        speech.submit(() ->
        {
            if (epoch.get() != asked)
            {
                queued.decrementAndGet();
                return; // hushed
            }
            try
            {
                long start = System.currentTimeMillis();
                byte[] wav = api.speak(part);
                long now = System.currentTimeMillis();
                Session session = sessions.get(guildId);
                if (session != null)
                    session.botSaid(part);
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
            finally
            {
                queued.decrementAndGet();
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

    /**
     * Leaves the voice channel (stopping the music and ?chat) once the
     * goodbye has been said: it waits until no speech is being synthesised
     * or playing, twice in a row, or 8 seconds.
     */
    private void leaveAfterGoodbye(Guild guild, long since, int quietChecks)
    {
        long guildId = guild.getIdLong();
        boolean quiet = queued(guildId).get() == 0 && bot.getPlayerManager().setUpHandler(guild).getSpeaker().isIdle();
        int checks = quiet ? quietChecks + 1 : 0;
        if (checks < 2 && System.currentTimeMillis() - since < 8000)
        {
            ticker.schedule(() -> leaveAfterGoodbye(guild, since, checks), 200, TimeUnit.MILLISECONDS);
            return;
        }
        LOG.info("Leaving the voice channel, as asked");
        bot.getPlayerManager().setUpHandler(guild).stopAndClear();
        bot.closeAudioConnection(guildId);
    }

    /** Sentences waiting to be synthesised and queued, per guild. */
    private AtomicInteger queued(long guildId)
    {
        return speechQueued.computeIfAbsent(guildId, id -> new AtomicInteger());
    }

    /**
     * Someone the bot is answering has been talking for TALKING_MS: pause its
     * speech (the music carries on). Once their line is transcribed it stops
     * for good, or resumes if that was its own voice through their speakers.
     */
    private void onTalking(long guildId, User user)
    {
        Session session = sessions.get(guildId);
        Guild guild = bot.getJDA().getGuildById(guildId);
        if (session == null || guild == null || session.lastAnswered != user.getIdLong())
            return;
        VoiceSpeaker speaker = bot.getPlayerManager().setUpHandler(guild).getSpeaker();
        if (speaker.isIdle() && queued(guildId).get() == 0)
            return;
        session.talkedOver = user.getIdLong();
        speaker.getPlayer().setPaused(true);
        LOG.info("{} is talking over the bot; pausing its speech", user.getName());
        // in case the line never arrives (dropped as too short, say)
        long uid = user.getIdLong();
        ticker.schedule(() -> endTalkOver(guild, session, uid, false), 10, TimeUnit.SECONDS);
    }

    /** Ends a pause from onTalking: stop the bot's speech for good, or carry on. */
    private void endTalkOver(Guild guild, Session session, long userId, boolean stop)
    {
        if (session.talkedOver != userId)
            return;
        session.talkedOver = 0;
        if (stop)
        {
            LOG.info("Stopped talking: they talked over the bot");
            hush(guild);
        }
        bot.getPlayerManager().setUpHandler(guild).getSpeaker().getPlayer().setPaused(false);
    }

    /**
     * Whether a transcript is mostly the bot's own recent words, which happens
     * when someone without headphones has the bot coming out of their speakers.
     */
    static boolean isEcho(String heard, List<String> botSaid)
    {
        List<String> words = words(heard);
        if (words.isEmpty() || botSaid.isEmpty())
            return false;
        if (words.size() == 1)
            return botSaid.stream().anyMatch(b -> words(b).equals(words));
        Set<String> said = new java.util.HashSet<>();
        botSaid.forEach(b -> said.addAll(words(b)));
        long matching = words.stream().filter(said::contains).count();
        return matching >= Math.ceil(words.size() * 0.75);
    }

    private static List<String> words(String text)
    {
        List<String> out = new ArrayList<>();
        for (String w : text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}' ]", " ").split("\\s+"))
            if (!w.isBlank())
                out.add(w);
        return out;
    }

    /**
     * "Not you, bot": stop talking, apologise briefly, and log what it wrongly
     * answered to voice-feedback.jsonl, so the router can be tuned (and the
     * line added to its benchmark).
     */
    private void notYou(Guild guild, Session session, String name, String said, long heardAt)
    {
        hush(guild);
        session.lastAnswered = 0;
        Answer wrong = session.lastResponse;
        session.lastResponse = null;
        if (wrong != null && System.currentTimeMillis() - wrong.time() < 120_000)
        {
            LOG.warn("Misrouted: {} said \"{}\" and the bot took it as {} [{}]{}; {} said: {}", wrong.speaker(), wrong.line(),
                    wrong.command(), wrong.args(), wrong.named() ? " (named)" : wrong.followUp() ? " (follow-up)" : "", name, said);
            org.json.JSONObject entry = new org.json.JSONObject()
                    .put("time", java.time.Instant.ofEpochMilli(wrong.time()).toString())
                    .put("speaker", wrong.speaker()).put("line", wrong.line())
                    .put("routed", wrong.command()).put("args", wrong.args())
                    .put("named", wrong.named()).put("followUp", wrong.followUp())
                    .put("conversation", wrong.conversation())
                    .put("correction", name + ": " + said);
            synchronized (FEEDBACK)
            {
                try
                {
                    Files.writeString(FEEDBACK, entry + "\n", java.nio.charset.StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                }
                catch (java.io.IOException e)
                {
                    LOG.warn("Could not write {}", FEEDBACK, e);
                }
            }
        }
        speak(guild, "Oops, sorry. Carry on.", heardAt);
    }

    /** The current date and time, for reminders and "what's on tonight". */
    private String timeContext()
    {
        return "Time: " + java.time.ZonedDateTime.now(zone)
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a (VV)", java.util.Locale.ENGLISH)) + ".";
    }

    private static List<Long> peopleInVoice(Guild guild)
    {
        List<Long> ids = new ArrayList<>();
        var state = guild.getSelfMember().getVoiceState();
        if (state != null && state.getChannel() != null)
            state.getChannel().getMembers().forEach(m -> {
                if (!m.getUser().isBot())
                    ids.add(m.getIdLong());
            });
        return ids;
    }

    private String memoryContext(List<Long> people)
    {
        String known = memory.describe(people);
        return known.isEmpty() ? "" : "What you remember about people here: " + known;
    }

    private void remember(User user, String name, String fact)
    {
        memory.remember(user.getIdLong(), name, fact);
        LOG.info("Remembering about {}: {}", name, fact);
    }

    /** Reminders and forgetting, by voice or text. Returns what to say back. */
    private String personal(LlmClient.Decision d, Member member, MessageChannel channel, String reply)
    {
        if (member == null)
            return "Sorry, I couldn't work out who you are.";
        switch (d.action)
        {
            case REMIND:
                reminders.add(member.getGuild().getIdLong(), channel.getIdLong(), member.getIdLong(),
                        member.getEffectiveName(), d.task, d.minutes);
                return reply.isEmpty() ? "Reminder set." : reply;
            case CANCEL_REMINDERS:
                int n = reminders.cancel(member.getGuild().getIdLong(), member.getIdLong());
                return n == 0 ? "You don't have any reminders with me." : n == 1 ? "Done, I've cancelled your reminder."
                        : "Done, I've cancelled your " + n + " reminders.";
            case FORGET:
                memory.forget(member.getIdLong());
                LOG.info("Forgot everything about {}", member.getEffectiveName());
                return reply.isEmpty() ? "Done. I've forgotten what I knew about you." : reply;
            default:
                return reply;
        }
    }

    /** A reminder came due: ping them in the channel, and say it if ?chat is on there. */
    private void remind(Reminders.Reminder r)
    {
        Guild guild = bot.getJDA().getGuildById(r.guildId);
        if (guild == null)
            return;
        LOG.info("Reminder for {}: {}", r.name, r.text);
        if (guild.getGuildChannelById(r.channelId) instanceof MessageChannel channel)
            channel.sendMessage("\u23F0 <@" + r.userId + "> Reminder: " + r.text)
                    .setAllowedMentions(java.util.EnumSet.of(net.dv8tion.jda.api.entities.Message.MentionType.USER))
                    .queue(m -> {}, e -> LOG.warn("Could not post a reminder", e));
        Session session = sessions.get(r.guildId);
        if (session != null)
            deliver(guild, session, r.userId, r.name + ", here's your reminder: " + r.text + ".", System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ text chat

    private static final String TEXT_MODE = "This message was typed in the Discord text channel, not spoken. Your "
            + "reply will be posted there as a message, so it can be up to five sentences and use Discord markdown and "
            + "links. Put everything in reply and leave post empty.";

    /**
     * Someone @mentioned the bot (or replied to it) in a text channel: the same
     * brain as ?chat, answering in text. Music requests run the real commands
     * as them; research comes back as a reply when it's done.
     */
    public void replyToText(net.dv8tion.jda.api.entities.Message message, String text)
    {
        Member member = message.getMember();
        if (llm == null || member == null || text.isBlank())
        {
            if (llm == null)
                message.reply("My brain isn't set up: `llmkey` is empty in the config.").queue();
            return;
        }
        Guild guild = message.getGuild();
        MessageChannel channel = message.getChannel();
        String name = member.getEffectiveName();
        LlmClient.Conversation conversation = textConversations.computeIfAbsent(channel.getIdLong(),
                id -> brainFor(channel).newConversation());
        List<Long> people = new ArrayList<>(List.of(member.getIdLong()));
        message.getMentions().getMembers().forEach(m -> people.add(m.getIdLong()));
        String player = playerState(bot.getPlayerManager().setUpHandler(guild));
        String context = TEXT_MODE + " " + timeContext() + " Player: " + player + " " + memoryContext(people);
        channel.sendTyping().queue();
        long start = System.currentTimeMillis();
        conversation.decide(name, text, context, null).whenCompleteAsync((d, e) ->
        {
            if (e != null)
            {
                LOG.warn("LLM request failed for a text message", e);
                textReply(message, "Sorry, my brain isn't responding right now.");
                return;
            }
            LOG.info("Text from {} in #{} ({} ms): {} -> {} [{}] {}", name, channel.getName(), System.currentTimeMillis() - start,
                    text, d.action, d.action == VoiceCommand.Type.RESEARCH || d.action == VoiceCommand.Type.REMIND ? d.task : d.query,
                    d.reply);
            if (!d.remember.isEmpty())
                remember(member.getUser(), name, d.remember);
            String reply = d.post.isEmpty() ? d.reply : (d.reply + "\n" + d.post).trim();
            switch (d.action)
            {
                case PLAY:   textCommand(message, member, reply, "play", d.query); return;
                case RADIO:  textCommand(message, member, reply, "radio", d.query); return;
                case MIX:    textCommand(message, member, reply, "mix", d.query); return;
                case SKIP:   textCommand(message, member, reply, "skip", ""); return;
                case STOP:   textCommand(message, member, reply, "stop", ""); return;
                case PAUSE:  textCommand(message, member, reply, "pause", ""); return;
                case RESUME: textCommand(message, member, reply, "resume", ""); return;
                case REMIND:
                case CANCEL_REMINDERS:
                case FORGET:
                    textReply(message, personal(d, member, channel, reply));
                    return;
                case RESEARCH:
                    textReply(message, reply.isEmpty() ? "Let me look into that." : reply);
                    researchForText(message, member, text, d.task, context);
                    return;
                default:
                    textReply(message, reply);
            }
        }, llmCalls);
    }

    private void textCommand(net.dv8tion.jda.api.entities.Message message, Member member, String reply, String command, String args)
    {
        if (!reply.isBlank())
            textReply(message, reply);
        // skip as a DJ is a forceskip, as by voice
        runCommand(member, message.getChannel(), command, args);
    }

    private void researchForText(net.dv8tion.jda.api.entities.Message message, Member member, String text, String task, String context)
    {
        if (researcher == null)
        {
            textReply(message, "I can't look things up at the moment, sorry.");
            return;
        }
        String name = member.getEffectiveName();
        long start = System.currentTimeMillis();
        LOG.info("Researching for {} (text) with {}: {}", name, researcher.getModel(), task);
        researcher.research(name, task, "What " + name + " wrote: " + text + "\n" + context, post ->
        {
            message.getChannel().sendMessage(post).setAllowedMentions(List.of()).queue();
            return "Posted.";
        }, false).whenComplete((r, e) ->
        {
            long secs = (System.currentTimeMillis() - start) / 1000;
            if (e != null || r.say.isBlank())
            {
                LOG.warn("Research failed after {} s: {}", secs, task, e);
                textReply(message, "Sorry, I couldn't find a good answer to that one.");
                return;
            }
            LOG.info("Research done in {} s ({} searches, ${}): {}", secs, r.searches, String.format("%.4f", r.cost), r.say);
            textReply(message, r.say);
        });
    }

    /**
     * The 18+ brain for a channel Discord marks age-restricted (a voice
     * channel, its built-in text chat, or a text channel), otherwise the normal one.
     */
    private LlmClient brainFor(Object channel)
    {
        if (nsfwLlm != null && channel instanceof net.dv8tion.jda.api.entities.channel.attribute.IAgeRestrictedChannel c && c.isNSFW())
        {
            if (nsfwLlm.isReachable())
                return nsfwLlm;
            LOG.warn("The 18+ model at {} isn't answering (PC off?); using the normal one", nsfwLlm.getModel());
        }
        return llm;
    }

    /** Replies to a message, with no pings. */
    private static void textReply(net.dv8tion.jda.api.entities.Message message, String text)
    {
        if (text == null || text.isBlank())
            return;
        String t = text.length() > 1990 ? text.substring(0, 1990) + "…" : text;
        message.reply(t).setAllowedMentions(List.of()).mentionRepliedUser(false).queue();
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
            endTalkOver(guild, session, user.getIdLong(), false);
            return;
        }
        String text;
        long start = System.currentTimeMillis();
        long uid = user.getIdLong();
        try
        {
            text = api.transcribe(wav);
        }
        catch (Exception e)
        {
            LOG.warn("STT request failed", e);
            endTalkOver(guild, session, uid, false);
            return;
        }
        long sttMs = System.currentTimeMillis() - start;
        long audioMs = VoiceAudio.durationMs(wav.length - 44);
        boolean echo = !text.isBlank() && isEcho(text, session.botSaidRecently());
        // they talked over the bot: if it was really them, it stops; if it
        // was its own voice through their speakers, it carries on
        endTalkOver(guild, session, uid, !text.isBlank() && !echo);
        if (text.isBlank())
            return;
        if (echo)
        {
            LOG.info("Ignored {} ({} ms audio): the bot's own voice through their mic: {}", user.getName(), audioMs, text);
            return;
        }
        session.lastHeard = System.currentTimeMillis();

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
        String args = route == null ? "" : route.args;
        LOG.info("Heard {} ({}, router {} ms){}: {} -> {} [{}]", name, heard, routerMs, how, said, command, args);
        if (command.equals("not_you"))
        {
            notYou(guild, session, name, said, heardAt);
            return;
        }
        session.lastAnswered = uid;
        session.lastResponse = new Answer(System.currentTimeMillis(), name, said, command, args, named, followUp, recent);

        if (!command.equals("chat"))
        {
            run(guild, member, session, command, args, heardAt);
            return;
        }
        if (session.conversation == null)
        {
            // no chat LLM: fall back to the simple keyword commands
            VoiceCommand parsed = VoiceCommand.parse(said);
            runDecision(guild, member, session, new LlmClient.Decision(parsed.type, parsed.arg, ""), "", heardAt);
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
            LOG.info("LLM {} ms: {} [{}] {}", System.currentTimeMillis() - asked, d.action,
                    d.action == VoiceCommand.Type.RESEARCH ? d.task : d.query, d.reply);
            if (!d.post.isEmpty())
                post(session, d.post);
            if (!d.remember.isEmpty())
                remember(user, name, d.remember);
            if (d.action == VoiceCommand.Type.RESEARCH)
            {
                research(guild, session, user, name, said, d.task, LlmClient.forSpeech(d.reply), player, heardAt);
                return;
            }
            if (streamed)
            {
                // already spoken; just remember it for the router and follow-ups
                session.add(BOT_NAME, LlmClient.forSpeech(d.reply));
                session.lastSpoke = System.currentTimeMillis();
                return;
            }
            runDecision(guild, member, session, d, LlmClient.forSpeech(d.reply), heardAt);
        }, llmCalls);
    }

    /**
     * Something that needs looking up or thinking about: say a holding line,
     * work on it in the background, and come back with the answer when
     * there's a gap in the conversation, like a person who went to check.
     */
    private void research(Guild guild, Session session, User user, String name, String said, String task, String holding,
            String player, long heardAt)
    {
        if (researcher == null)
        {
            speak(guild, "I can't look things up at the moment, sorry.", heardAt);
            return;
        }
        if (session.research.size() >= MAX_RESEARCH)
        {
            speak(guild, "I'm still working on a few things. Ask me again in a minute.", heardAt);
            return;
        }
        speak(guild, holding.isEmpty() ? "Let me look into that." : holding, heardAt);
        String context = "What " + name + " said: " + said + "\n" + timeContext() + "\nPlayer: " + player
                + "\n" + memoryContext(peopleInVoice(guild))
                + "\nRecent conversation in the voice channel:\n" + String.join("\n", session.recent());
        long start = System.currentTimeMillis();
        LOG.info("Researching for {} with {}: {}", name, researcher.getModel(), task);
        CompletableFuture<Researcher.Result> job = researcher.research(name, task, context, text -> post(session, text), true);
        session.research.add(job);
        job.whenComplete((r, e) ->
        {
            session.research.remove(job);
            if (job.isCancelled() || sessions.get(guild.getIdLong()) != session)
                return; // they stopped ?chat
            long secs = (System.currentTimeMillis() - start) / 1000;
            if (e != null || r.say.isBlank())
            {
                LOG.warn("Research failed after {} s: {}", secs, task, e);
                deliver(guild, session, user.getIdLong(), "Sorry " + name + ", I couldn't find a good answer to that one.", System.currentTimeMillis());
                return;
            }
            LOG.info("Research done in {} s ({} searches, {} posts, ${}): {} Sources: {}", secs, r.searches, r.posts,
                    String.format("%.4f", r.cost), r.say, r.sources);
            deliver(guild, session, user.getIdLong(), r.say, System.currentTimeMillis());
        });
    }

    /** Says a research answer once nobody's talking (or after MAX_LULL_WAIT_MS), and treats it as answering them. */
    private void deliver(Guild guild, Session session, long userId, String say, long since)
    {
        if (sessions.get(guild.getIdLong()) != session)
            return;
        long now = System.currentTimeMillis();
        boolean busy = session.listener.isAnyoneSpeaking() || !session.turns.isEmpty()
                || now - session.lastHeard < LULL_MS || now - session.lastSpoke < LULL_MS
                || !bot.getPlayerManager().setUpHandler(guild).getSpeaker().isIdle();
        if (busy && now - since < MAX_LULL_WAIT_MS)
        {
            ticker.schedule(() -> deliver(guild, session, userId, say, since), 250, TimeUnit.MILLISECONDS);
            return;
        }
        session.lastAnswered = userId; // so their reply to it counts as a follow-up
        if (session.conversation != null)
            session.conversation.remember(say);
        speak(guild, say);
    }

    /** Posts in the session's text channel, with no pings. Returns the result for the LLM. */
    private String post(Session session, String text)
    {
        try
        {
            session.channel.sendMessage(text).setAllowedMentions(List.of()).queue(
                    m -> LOG.info("Posted in #{}: {}", session.channel.getName(), text.replace('\n', ' ')),
                    e -> LOG.warn("Could not post in #{}", session.channel.getName(), e));
            return "Posted.";
        }
        catch (Exception e)
        {
            LOG.warn("Could not post in #{}", session.channel.getName(), e);
            return "Posting failed: " + e.getMessage();
        }
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
        Guild guild = session.channel instanceof net.dv8tion.jda.api.entities.channel.middleman.GuildChannel gc ? gc.getGuild() : null;
        String context = timeContext() + " Player: " + player
                + (guild == null ? "" : " " + memoryContext(peopleInVoice(guild)))
                + (before.isEmpty() ? "" : " Recent conversation in the channel:\n" + String.join("\n", before));
        return session.conversation.decide(name, text, context, onSentence);
    }

    /** Acts on what the chat LLM (or the keyword fallback) decided. */
    private void runDecision(Guild guild, Member member, Session session, LlmClient.Decision d, String reply, long heardAt)
    {
        String query = d.query;
        switch (d.action)
        {
            case PLAY:   run(guild, member, session, "play", query, heardAt); break;
            case RADIO:  run(guild, member, session, "radio", query, heardAt); break;
            case MIX:    run(guild, member, session, "mix", query, heardAt); break;
            case REMIND:
            case CANCEL_REMINDERS:
            case FORGET:
                speak(guild, personal(d, member, session.channel, reply), heardAt);
                break;
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
        run(guild, member, session.channel, command, args, s -> speak(guild, s, heardAt));
    }

    /**
     * Runs a playback command for someone who asked by text: the command's
     * own replies go to the channel, and there's no spoken confirmation.
     */
    public void runCommand(Member member, MessageChannel channel, String command, String args)
    {
        run(member.getGuild(), member, channel, command, args, null);
    }

    /**
     * @param say speaks (or otherwise gives) a short confirmation or error, or
     *            null when the command's own replies in the channel are enough
     */
    private void run(Guild guild, Member member, MessageChannel channel, String command, String args, Consumer<String> say)
    {
        Consumer<String> confirm = say == null ? s -> {} : say;
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        switch (command)
        {
            case "pause":
                handler.getPlayer().setPaused(true);
                confirm.accept("Paused.");
                return;
            case "resume":
                handler.getPlayer().setPaused(false);
                confirm.accept("Resuming.");
                return;
            case "hush":
                hush(guild);
                return;
            case "leave":
                confirm.accept("Okay, I'm off. Catch you later.");
                leaveAfterGoodbye(guild, System.currentTimeMillis(), 0);
                return;
            case "volume":
                if (!args.matches("\\d{1,3}"))
                {
                    confirm.accept("How loud would you like it, out of a hundred and fifty?");
                    return;
                }
                break;
            case "play":
            case "radio":
                if (args.isBlank())
                {
                    confirm.accept(command.equals("play") ? "What should I play?" : "Which station?");
                    return;
                }
                break;
            default:
        }
        CommandClient c = client;
        if (c == null || member == null)
            return;
        AtomicBoolean spokeError = new AtomicBoolean();
        VoiceCommandEvent event = new VoiceCommandEvent(member, channel, args, c, reply ->
        {
            // speak the first error or warning, cleaned up; success replies are
            // covered by the confirmation
            if (say != null && (reply.startsWith(c.getError()) || reply.startsWith(c.getWarning()))
                    && spokeError.compareAndSet(false, true))
                say.accept(spokenReply(reply));
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
            confirm.accept(String.format(ack, args));
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
