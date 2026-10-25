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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Chat client for an OpenAI-compatible completions API (OpenRouter by
 * default). Each utterance is one call that both classifies it (a playback
 * command or just talk) and writes the spoken reply, returned as a
 * {@link Decision}. Each {@link Conversation} keeps the last few turns so the
 * bot can follow a back-and-forth in the voice channel.
 */
public class LlmClient
{
    private static final int HISTORY_MESSAGES = 16;

    // Appended to the configurable persona prompt; the code depends on this format.
    static final String ACTIONS = "\n\nYou also control the music bot you are part of. For every message, reply with "
            + "only a JSON object, no other text, with the keys in this order: {\"action\": one of \"play\", \"radio\", "
            + "\"mix\", \"skip\", \"pause\", \"resume\", \"stop\", \"research\", \"remind\", \"cancel_reminders\", "
            + "\"forget\", \"code\", \"allow_coder\", \"revoke_coder\", \"list_coders\", \"chat\"; \"query\": what to "
            + "search for (play, radio and mix), or the person's name (allow_coder and revoke_coder), otherwise empty; "
            + "\"task\": for research, remind and code only, otherwise empty; \"minutes\": for remind only, otherwise 0; \"remember\": "
            + "usually empty; \"post\": text to post in the Discord text channel, usually empty; \"reply\": what to say "
            + "out loud}. "
            + "Use play when they want a song, artist or album played or queued (query = what to search YouTube for), "
            + "radio for a live radio station or radio of a genre (query = station name or genre), mix for several "
            + "songs like a song or artist (query = that song or artist, empty for like what's playing), "
            + "skip to move to the next song, pause and resume to pause or continue, stop to stop the music and "
            + "clear the queue. A temporary halt (\"hang on\", \"stop it for a sec\", \"hold the music\") is pause; "
            + "use stop only when they want the music finished. When they leave the choice to you (\"something you'd "
            + "like\", \"surprise me\", \"put some music on\"), pick one specific real song yourself, suited to the "
            + "conversation and what's been playing, and use play with query = \"Artist - Title\"; never search for "
            + "a vague phrase. Use chat for everything else, including questions "
            + "about the current song, which is given in the context. Only choose a music action when they clearly "
            + "ask for it; otherwise chat. For music actions keep reply to a few words, like \"Skipping.\" "
            + "Use research when a good answer needs information you'd have to look up (news, sport results, "
            + "weather, prices, opening hours, release dates, anything recent or that you aren't sure of) or "
            + "careful thought (detailed explanations, comparisons, plans, recommendations they want done properly). "
            + "Then task = a self-contained description of what to find out or work through, with the details from "
            + "the conversation, and reply = a short, natural holding line, as a person would say before going to "
            + "check (\"Good question, let me look that up.\", \"Give me a minute on that one.\"); vary it. You'll "
            + "come back with the answer by yourself, so don't promise a time. Don't research small talk or things "
            + "you already know (capitals, definitions, history, how things work): just answer. Use post when they ask you to post, send, write down or share something in the chat, "
            + "or when a link or list is far easier to read than hear: plain text and links, no @mentions; then say "
            + "you've posted it; a request only to post something is chat. "
            + "Use remind when they ask to be reminded of something or for a timer: task = what to remind them of, "
            + "short (\"start the game\"), minutes = how many minutes from now (work out clock times from the time "
            + "in the context), reply = a short confirmation. Use cancel_reminders when they want their reminders "
            + "cancelled. When the speaker tells you something lasting about themselves that a friend would remember "
            + "(favourite music, teams, what they're into, plans, a running joke), or asks you to remember something, "
            + "set remember to one short fact in the third person (\"Regan supports the Blues\"); never remember "
            + "anything sensitive (health, money, passwords, addresses). Use forget when they ask you to forget what "
            + "you know about them. What you already remember about people is in the context; use it naturally, "
            + "without reciting it. "
            + "You can change your own code. Use code when they ask for a change to you, the bot: a new feature or "
            + "command, different behaviour, or a fix for something you got wrong or that's broken (not a music "
            + "request, and not a question about how you work). Then task = a short, self-contained description of the "
            + "change for a programmer, with every detail from the conversation, and reply = a short line that you'll "
            + "get to work on it; you'll report back by yourself when it's done. Use allow_coder when they ask to let "
            + "someone change your code and revoke_coder to stop them (query = that person's name), and list_coders "
            + "when they ask who can. "
            + "People call you Gunnabot, bot, robot or clanker. Every message you see was said to you, though not "
            + "always by name; the recent channel conversation in the context shows what they're responding to.";

    /** The decision as a JSON schema, keys in the order ReplyStream needs (reply last). */
    static final JSONObject DECISION_SCHEMA = new JSONObject()
            .put("type", "json_schema")
            .put("json_schema", new JSONObject().put("name", "decision").put("strict", true).put("schema", new JSONObject()
                    .put("type", "object")
                    .put("properties", new JSONObject()
                            .put("action", new JSONObject().put("type", "string").put("enum", new JSONArray()
                                    .put("play").put("radio").put("mix").put("skip").put("pause").put("resume").put("stop")
                                    .put("research").put("remind").put("cancel_reminders").put("forget").put("code")
                                    .put("allow_coder").put("revoke_coder").put("list_coders").put("chat")))
                            .put("query", new JSONObject().put("type", "string"))
                            .put("task", new JSONObject().put("type", "string"))
                            .put("minutes", new JSONObject().put("type", "number"))
                            .put("remember", new JSONObject().put("type", "string"))
                            .put("post", new JSONObject().put("type", "string"))
                            .put("reply", new JSONObject().put("type", "string")))
                    .put("required", new JSONArray().put("action").put("query").put("task").put("minutes")
                            .put("remember").put("post").put("reply"))));

    private final HttpClient http;
    // reading a streamed answer blocks, so it gets its own threads
    private final ExecutorService readers = Executors.newCachedThreadPool(r ->
    {
        Thread t = new Thread(r, "llm-stream");
        t.setDaemon(true);
        return t;
    });
    private final String baseUrl, apiKey, model, systemPrompt;
    private final int maxTokens;
    private final boolean openRouter;

    public LlmClient(String baseUrl, String apiKey, String model, String persona, int maxTokens)
    {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.systemPrompt = persona + ACTIONS;
        this.maxTokens = maxTokens;
        this.openRouter = this.baseUrl.contains("openrouter.ai");
        // a plain-http local server (LM Studio) never answers Java's HTTP/2 upgrade request
        this.http = HttpClient.newBuilder()
                .version(this.baseUrl.startsWith("http://") ? HttpClient.Version.HTTP_1_1 : HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String getModel()
    {
        return model;
    }

    private volatile long checkedAt;
    private volatile boolean reachable;

    /** Whether the API answers (checked at most once a minute), for a local server that may be switched off. */
    public boolean isReachable()
    {
        if (System.currentTimeMillis() - checkedAt < 60_000)
            return reachable;
        boolean ok;
        try
        {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/models")).timeout(Duration.ofSeconds(2))
                    .header("Authorization", "Bearer " + apiKey).GET().build();
            ok = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        }
        catch (IOException e)
        {
            ok = false;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            ok = false;
        }
        reachable = ok;
        checkedAt = System.currentTimeMillis();
        return ok;
    }

    public Conversation newConversation()
    {
        return new Conversation();
    }

    /** What the LLM decided to do with an utterance. */
    public static class Decision
    {
        public final VoiceCommand.Type action;
        /**
         * query: a music search, or a person's name for the coder whitelist;
         * task: what to research, the reminder, or the change to make to the bot;
         * remember: a fact about the speaker to keep; post: text for the Discord channel
         */
        public final String query, task, remember, post, reply;
        /** For a reminder: minutes from now. */
        public final double minutes;
        /** How many sentences of the reply were already spoken while it streamed in. */
        public final int streamed;

        Decision(VoiceCommand.Type action, String query, String reply)
        {
            this(action, query, "", 0, "", "", reply, 0);
        }

        Decision(VoiceCommand.Type action, String query, String task, double minutes, String remember, String post,
                String reply, int streamed)
        {
            this.action = action;
            this.query = query;
            this.task = task;
            this.minutes = minutes;
            this.remember = remember;
            this.post = post;
            this.reply = reply;
            this.streamed = streamed;
        }

        Decision withStreamed(int sentences)
        {
            return new Decision(action, query, task, minutes, remember, post, reply, sentences);
        }
    }

    public class Conversation
    {
        private final Deque<JSONObject> history = new ArrayDeque<>();

        /** Adds something the bot said outside a decide() call (a research answer) to the history. */
        public synchronized void remember(String said)
        {
            JSONObject reply = new JSONObject().put("action", "chat").put("query", "").put("task", "").put("minutes", 0)
                    .put("remember", "").put("post", "").put("reply", said);
            history.add(new JSONObject().put("role", "assistant").put("content", reply.toString()));
            while (history.size() > HISTORY_MESSAGES)
                history.removeFirst();
        }

        /**
         * Classifies what a speaker said and writes the reply, keeping both in
         * the history. The answer streams in, and each sentence of a chat
         * reply goes to onSentence as soon as it's written. Cancelling the
         * returned future aborts the request and leaves the history
         * untouched, so a reply nobody will hear (they kept talking, or it
         * turned out to be a command) isn't remembered.
         * @param context a line describing the player state ("Now playing: ...")
         * @param onSentence receives spoken sentences as they arrive, or null to wait for the whole answer
         */
        public CompletableFuture<Decision> decide(String speaker, String text, String context, Consumer<String> onSentence)
        {
            JSONObject user = new JSONObject().put("role", "user").put("content", speaker + ": " + text);
            // OpenRouter: the context goes in its own system message just before the line, so the
            // long system prompt stays the same and is cached. Local chat templates (Qwen in
            // LM Studio) reject a system message after the conversation, so it joins the first.
            JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system")
                    .put("content", openRouter ? systemPrompt : systemPrompt + "\n\nContext: " + context));
            synchronized (this)
            {
                history.forEach(messages::put);
            }
            if (openRouter)
                messages.put(new JSONObject().put("role", "system").put("content", "Context: " + context));
            messages.put(user);

            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", maxTokens)
                    .put("stream", true)
                    // local servers (LM Studio, llama.cpp) take a schema, not json_object; the
                    // grammar also stops "thinking" models writing their reasoning out first
                    .put("response_format", openRouter ? new JSONObject().put("type", "json_object") : DECISION_SCHEMA)
                    .put("messages", messages);
            if (openRouter)
            {
                // models like DeepSeek V4 think by default: that took 2-15 s and
                // could use up max_tokens before writing any reply
                body.put("reasoning", new JSONObject().put("enabled", false));
                body.put("provider", new JSONObject().put("sort", "latency"));
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    // OpenRouter attribution header; ignored by other providers
                    .header("X-Title", "Gunna bot")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            AtomicReference<Stream<String>> stream = new AtomicReference<>();
            CompletableFuture<HttpResponse<Stream<String>>> call = http.sendAsync(req, HttpResponse.BodyHandlers.ofLines());
            CompletableFuture<Decision> result = call.thenApplyAsync(res ->
            {
                stream.set(res.body());
                try (Stream<String> lines = res.body())
                {
                    if (res.statusCode() != 200)
                        throw new IOException("LLM failed: HTTP " + res.statusCode() + " "
                                + lines.collect(Collectors.joining(" ")));
                    ReplyStream reply = new ReplyStream(onSentence);
                    readEvents(lines, reply);
                    reply.finish();
                    String content = reply.content().trim();
                    synchronized (this)
                    {
                        history.add(user);
                        history.add(new JSONObject().put("role", "assistant").put("content", content));
                        while (history.size() > HISTORY_MESSAGES)
                            history.removeFirst();
                    }
                    Decision d = parseDecision(content);
                    return d.withStreamed(reply.sentences());
                }
                catch (IOException | UncheckedIOException e)
                {
                    throw new CompletionException(e);
                }
            }, readers);
            // cancelling the result doesn't reach the HTTP call by itself;
            // closing the body stops a response that's already arriving
            result.whenComplete((d, e) ->
            {
                if (!result.isCancelled())
                    return;
                call.cancel(true);
                Stream<String> lines = stream.get();
                if (lines != null)
                    lines.close();
            });
            return result;
        }
    }

    /** Reads server-sent events ("data: {json}") and feeds the text deltas to the reply. */
    static void readEvents(Stream<String> lines, ReplyStream reply) throws IOException
    {
        for (String line : (Iterable<String>) lines::iterator)
        {
            // OpenRouter also sends ": OPENROUTER PROCESSING" keep-alive comments
            if (!line.startsWith("data:"))
                continue;
            String data = line.substring(5).trim();
            if (data.equals("[DONE]"))
                return;
            JSONObject event = new JSONObject(data);
            if (event.has("error"))
                throw new IOException("LLM failed mid-stream: " + event.get("error"));
            JSONArray choices = event.optJSONArray("choices");
            if (choices == null || choices.isEmpty())
                continue;
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta != null && delta.opt("content") instanceof String piece)
                reply.feed(piece);
        }
    }

    /**
     * Parses the model's JSON. Models sometimes wrap it in a code fence or add
     * a sentence, so this takes the outermost {...}; plain text with no JSON
     * is treated as a chat reply.
     */
    static Decision parseDecision(String content)
    {
        int start = content.indexOf('{'), end = content.lastIndexOf('}');
        if (start < 0 || end <= start)
            return new Decision(VoiceCommand.Type.CHAT, "", content.trim());
        JSONObject o;
        try
        {
            o = new JSONObject(content.substring(start, end + 1));
        }
        catch (JSONException e)
        {
            return new Decision(VoiceCommand.Type.CHAT, "", content.trim());
        }
        String query = o.optString("query", "").trim();
        String task = o.optString("task", "").trim();
        String post = o.optString("post", "").trim();
        String remember = o.optString("remember", "").trim();
        double minutes = o.optDouble("minutes", 0);
        if (Double.isNaN(minutes))
            minutes = 0;
        String reply = o.optString("reply", "").trim();
        VoiceCommand.Type action;
        switch (o.optString("action", "chat").trim().toLowerCase(Locale.ROOT))
        {
            // play with nothing to play: a bare "play" (no reply) means resume, but models
            // also label "what should we listen to?" or "post that link" as play
            case "play":   action = !query.isEmpty() ? VoiceCommand.Type.PLAY
                    : post.isEmpty() && reply.isEmpty() ? VoiceCommand.Type.RESUME : VoiceCommand.Type.CHAT; break;
            case "radio":  action = query.isEmpty() ? VoiceCommand.Type.CHAT : VoiceCommand.Type.RADIO; break;
            case "mix":    action = VoiceCommand.Type.MIX; break;
            case "skip":   action = VoiceCommand.Type.SKIP; break;
            case "pause":  action = VoiceCommand.Type.PAUSE; break;
            case "resume": action = VoiceCommand.Type.RESUME; break;
            case "stop":   action = VoiceCommand.Type.STOP; break;
            case "research": action = task.isEmpty() ? VoiceCommand.Type.CHAT : VoiceCommand.Type.RESEARCH; break;
            case "remind": action = task.isEmpty() || minutes <= 0 ? VoiceCommand.Type.CHAT : VoiceCommand.Type.REMIND; break;
            case "cancel_reminders": action = VoiceCommand.Type.CANCEL_REMINDERS; break;
            case "forget": action = VoiceCommand.Type.FORGET; break;
            case "code":   action = task.isEmpty() ? VoiceCommand.Type.CHAT : VoiceCommand.Type.CODE; break;
            case "allow_coder":  action = VoiceCommand.Type.ALLOW_CODER; break;
            case "revoke_coder": action = VoiceCommand.Type.REVOKE_CODER; break;
            case "list_coders":  action = VoiceCommand.Type.LIST_CODERS; break;
            default:       action = VoiceCommand.Type.CHAT;
        }
        return new Decision(action, query, task, minutes, remember, post, reply, 0);
    }

    /** Strips markdown and emoji so the reply reads cleanly through text-to-speech. */
    public static String forSpeech(String reply)
    {
        return reply
                .replaceAll("```[\\s\\S]*?```", " ")
                .replaceAll("[*_`#>~|]", "")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
