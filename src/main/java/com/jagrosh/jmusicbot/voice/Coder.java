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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Self-editing: people on the whitelist ask the bot (in ?chat or by
 * @mention) to change its own code. The request goes to the coder sidecar
 * (coder/ in this repo), which runs Claude Code on a checkout, tests the
 * change, pushes it to master and watches the deploy. The bot posts a short
 * update when the work is done and says it's restarting; after the deploy the
 * new process reads {@link #RESTART} to rejoin the voice channel.
 *
 * The owner is always allowed and is the only one who can change the
 * whitelist, which is kept in coders.json.
 *
 * Anyone can ask how the bot works ({@link #ask}): the sidecar answers from
 * the source code with Claude Code's read-only tools.
 */
public class Coder
{
    private static final Logger LOG = LoggerFactory.getLogger(Coder.class);

    static final Path WHITELIST = Path.of("coders.json");
    static final Path RESTART = Path.of("restart.json");
    private static final long POLL_SECONDS = 15;
    // answers are for a live conversation, so they're checked on often
    private static final long ASK_POLL_MS = 3000;
    private static final long ASK_TIMEOUT_MS = 6 * 60_000;
    // a restart note older than this is from some other restart, not a deploy
    private static final long RESTART_MAX_AGE_MS = 30 * 60_000;

    /** Where a request's progress goes: a message in the channel, and speech if ?chat is on. */
    public interface Updates
    {
        void post(String text);

        void say(String text);
    }

    /** Where the bot was when it went down for a deploy, so the new process can pick up there. */
    public record Restart(long guildId, long textChannelId, long voiceChannelId, boolean listening, String summary) {}

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl, apiKey;
    private final long ownerId;
    private final ScheduledExecutorService ticker;
    private final Path whitelistFile;
    private final JSONObject whitelist; // user id -> name

    /**
     * @param baseUrl the sidecar, e.g. http://musicbot-coder:8787, or empty when it isn't set up
     */
    public Coder(String baseUrl, String apiKey, long ownerId, ScheduledExecutorService ticker)
    {
        this(baseUrl, apiKey, ownerId, ticker, WHITELIST);
    }

    Coder(String baseUrl, String apiKey, long ownerId, ScheduledExecutorService ticker, Path whitelistFile)
    {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.ownerId = ownerId;
        this.ticker = ticker;
        this.whitelistFile = whitelistFile;
        this.whitelist = load(whitelistFile);
    }

    public boolean isEnabled()
    {
        return !baseUrl.isEmpty();
    }

    public synchronized boolean isAllowed(long userId)
    {
        return userId == ownerId || whitelist.has(Long.toString(userId));
    }

    // ------------------------------------------------------------------ whitelist

    /**
     * Adds someone to the whitelist, or takes them off. Only the owner can.
     * @param name who they asked for, as said or typed
     * @param candidates members the name could mean: @mentions first, then people in the voice channel
     * @return what to tell them
     */
    public String setAllowed(Member by, String name, List<Member> candidates, boolean allow)
    {
        if (by == null || by.getIdLong() != ownerId)
            return "Only the owner can change who's allowed to edit my code.";
        Member who = pick(name, candidates);
        if (who == null)
            who = search(by.getGuild(), name);
        if (who == null)
            return "I couldn't work out who " + (name.isBlank() ? "you meant" : name) + " is. Try @mentioning them.";
        if (who.getIdLong() == ownerId)
            return "You can always change my code.";
        String id = who.getId(), shown = who.getEffectiveName();
        synchronized (this)
        {
            boolean had = whitelist.has(id);
            if (allow == had)
                return shown + (allow ? " can already change my code." : " wasn't on the list anyway.");
            if (allow)
                whitelist.put(id, shown);
            else
                whitelist.remove(id);
            save();
        }
        LOG.info("{} {} the coding whitelist", shown, allow ? "added to" : "removed from");
        return allow ? "Done, " + shown + " can now ask me to change my code." : "Done, " + shown + " can't change my code any more.";
    }

    public synchronized String describeWhitelist()
    {
        if (whitelist.isEmpty())
            return "Only the owner can change my code at the moment.";
        List<String> names = new ArrayList<>();
        whitelist.keySet().forEach(k -> names.add(whitelist.getString(k)));
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return "Besides the owner, " + String.join(", ", names) + (names.size() == 1 ? " is" : " are") + " allowed to change my code.";
    }

    /** The one member whose name matches, or null if none or several do. */
    static Member pick(String name, List<Member> candidates)
    {
        if (candidates.size() == 1 && name.isBlank())
            return candidates.get(0);
        String n = clean(name);
        if (n.isEmpty())
            return null;
        List<Member> exact = new ArrayList<>(), partial = new ArrayList<>();
        for (Member m : candidates)
        {
            for (String s : List.of(m.getEffectiveName(), m.getUser().getName()))
            {
                String c = clean(s);
                if (c.equals(n))
                {
                    exact.add(m);
                    break;
                }
                if (c.startsWith(n) || c.contains(n))
                {
                    partial.add(m);
                    break;
                }
            }
        }
        if (exact.size() == 1)
            return exact.get(0);
        return exact.isEmpty() && partial.size() == 1 ? partial.get(0) : null;
    }

    private static String clean(String s)
    {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("^@", "").replaceAll("[^\\p{L}\\p{N}]", "");
    }

    /** Asks Discord for members whose name starts with this (works without the members intent). */
    private static Member search(Guild guild, String name)
    {
        String n = name.replaceAll("^@", "").trim();
        if (n.isEmpty())
            return null;
        try
        {
            List<Member> found = guild.retrieveMembersByPrefix(n, 5).setTimeout(5, TimeUnit.SECONDS).get();
            return found.size() == 1 ? found.get(0) : pick(n, found);
        }
        catch (Exception e)
        {
            LOG.warn("Member search for {} failed", n, e);
            return null;
        }
    }

    private static JSONObject load(Path file)
    {
        try
        {
            return Files.exists(file) ? new JSONObject(Files.readString(file)) : new JSONObject();
        }
        catch (Exception e)
        {
            LOG.warn("Could not read {}; starting with an empty whitelist", file, e);
            return new JSONObject();
        }
    }

    private void save()
    {
        try
        {
            Files.writeString(whitelistFile, whitelist.toString(2), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            LOG.warn("Could not write {}", whitelistFile, e);
        }
    }

    // ------------------------------------------------------------------ requests

    /**
     * Sends a change request to the sidecar and follows it until it's done,
     * reporting through updates. Returns what to say straight away.
     * @param holding the LLM's "on it" line
     * @param onDeploy called once the change is pushed and the deploy has
     *                 started, with the summary, so the caller can say it's
     *                 restarting and note where to come back to
     */
    public String request(Member requester, String task, String holding, Updates updates, java.util.function.Consumer<String> onDeploy)
    {
        if (!isEnabled())
            return "I can't change my own code: the coder isn't set up.";
        if (requester == null || !isAllowed(requester.getIdLong()))
            return "Sorry, only people the owner has approved can change my code.";
        JSONObject res;
        try
        {
            res = call("POST", "/tasks", new JSONObject()
                    .put("request", task)
                    .put("requester", requester.getEffectiveName())
                    .put("requesterId", requester.getId()));
        }
        catch (Exception e)
        {
            LOG.warn("Coder request failed", e);
            return "I can't reach my coding setup right now, sorry.";
        }
        String id = res.getString("id");
        int ahead = res.optInt("position", 0);
        LOG.info("Coding task {} for {}: {}", id, requester.getEffectiveName(), task);
        AtomicReference<ScheduledFuture<?>> poll = new AtomicReference<>();
        AtomicReference<String> seen = new AtomicReference<>("");
        poll.set(ticker.scheduleWithFixedDelay(() -> follow(id, seen, poll, updates, onDeploy),
                POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS));
        String lead = holding == null || holding.isBlank() ? "On it." : holding;
        return ahead > 0 ? lead + " There's " + (ahead == 1 ? "one change" : ahead + " changes") + " ahead of it." : lead;
    }

    /** One poll of a task: reports when it reaches a new state, and stops when it's finished. */
    private void follow(String id, AtomicReference<String> seen, AtomicReference<ScheduledFuture<?>> poll, Updates updates,
            java.util.function.Consumer<String> onDeploy)
    {
        JSONObject t;
        try
        {
            t = call("GET", "/tasks/" + id, null);
        }
        catch (Exception e)
        {
            LOG.debug("Coder poll failed for {}", id, e);
            return; // the sidecar may be restarting; try again next time
        }
        String state = t.optString("state");
        if (state.equals(seen.getAndSet(state)))
            return;
        String summary = t.optString("summary", "").trim();
        LOG.info("Coding task {}: {} {}", id, state, summary);
        switch (state)
        {
            case "done":
                // finished without a change to deploy (nothing needed, or Claude declined)
                stop(poll);
                updates.post("🛠️ " + (summary.isEmpty() ? "Finished, but nothing needed changing." : summary));
                updates.say(summary.isEmpty() ? "That's done, nothing needed changing." : summary);
                break;
            case "failed":
                stop(poll);
                String why = t.optString("error", "").trim();
                updates.post("⚠️ That change didn't make it: " + (why.isEmpty() ? "something went wrong." : why)
                        + (summary.isEmpty() ? "" : "\n> " + summary.replace("\n", "\n> ")));
                updates.say("Sorry, that change didn't work out. I've posted the details.");
                break;
            case "deploying":
                // keep polling: if the deploy fails, this process is still here to say so
                onDeploy.accept(summary);
                break;
            case "deployed":
                stop(poll); // the new version is up; it does the talking (see VoiceManager.resumeAfterRestart)
                break;
            case "deploy_failed":
                stop(poll);
                clearRestart();
                updates.post("⚠️ The deploy failed, so I'm still running the old version. " + t.optString("error", ""));
                updates.say("The deploy failed, so I'm still on the old version.");
                break;
            default: // queued, running, testing, pushing
        }
    }

    /**
     * Asks the sidecar a question about the bot's own code, answered from
     * the source with read-only tools.
     * @param spoken whether the answer will be said out loud (short and plain) or posted
     * @return the answer, in the bot's voice
     */
    public java.util.concurrent.CompletableFuture<String> ask(String question, String requester, boolean spoken)
    {
        java.util.concurrent.CompletableFuture<String> answer = new java.util.concurrent.CompletableFuture<>();
        if (!isEnabled())
        {
            answer.completeExceptionally(new IllegalStateException("the coder isn't set up"));
            return answer;
        }
        String id;
        try
        {
            id = call("POST", "/asks", new JSONObject().put("question", question).put("requester", requester)
                    .put("spoken", spoken)).getString("id");
        }
        catch (Exception e)
        {
            answer.completeExceptionally(e);
            return answer;
        }
        long start = System.currentTimeMillis();
        AtomicReference<ScheduledFuture<?>> poll = new AtomicReference<>();
        poll.set(ticker.scheduleWithFixedDelay(() ->
        {
            try
            {
                JSONObject a = call("GET", "/asks/" + id, null);
                switch (a.optString("state"))
                {
                    case "done" -> answer.complete(a.optString("answer", "").trim());
                    case "failed" -> answer.completeExceptionally(new IOException(a.optString("error")));
                    default ->
                    {
                        if (System.currentTimeMillis() - start > ASK_TIMEOUT_MS)
                            answer.completeExceptionally(new IOException("no answer after " + ASK_TIMEOUT_MS / 1000 + " s"));
                    }
                }
            }
            catch (Exception e)
            {
                LOG.debug("Ask poll failed for {}", id, e);
            }
            if (answer.isDone())
                stop(poll);
        }, ASK_POLL_MS, ASK_POLL_MS, TimeUnit.MILLISECONDS));
        return answer;
    }

    private static void stop(AtomicReference<ScheduledFuture<?>> poll)
    {
        ScheduledFuture<?> f = poll.get();
        if (f != null)
            f.cancel(false);
    }

    private JSONObject call(String method, String path, JSONObject body) throws IOException, InterruptedException
    {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiKey);
        if (body == null)
            req.GET();
        else
            req.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2)
            throw new IOException("Coder " + method + " " + path + ": HTTP " + res.statusCode() + " " + res.body());
        return new JSONObject(res.body());
    }

    // ------------------------------------------------------------------ restarts

    /** Notes where the bot is, for the new process after the deploy. */
    public static void saveRestart(Restart r)
    {
        JSONObject o = new JSONObject()
                .put("time", System.currentTimeMillis())
                .put("guild", Long.toString(r.guildId()))
                .put("textChannel", Long.toString(r.textChannelId()))
                .put("voiceChannel", Long.toString(r.voiceChannelId()))
                .put("listening", r.listening())
                .put("summary", r.summary());
        try
        {
            Files.writeString(RESTART, o.toString(2), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            LOG.warn("Could not write {}", RESTART, e);
        }
    }

    /** Reads and deletes the restart note, or returns null if there isn't a recent one. */
    public static Restart takeRestart()
    {
        return takeRestart(RESTART, System.currentTimeMillis());
    }

    static Restart takeRestart(Path file, long now)
    {
        if (!Files.exists(file))
            return null;
        try
        {
            JSONObject o = new JSONObject(Files.readString(file));
            Files.deleteIfExists(file);
            if (now - o.optLong("time") > RESTART_MAX_AGE_MS)
                return null;
            return new Restart(Long.parseLong(o.getString("guild")), Long.parseLong(o.optString("textChannel", "0")),
                    Long.parseLong(o.optString("voiceChannel", "0")), o.optBoolean("listening"), o.optString("summary", ""));
        }
        catch (Exception e)
        {
            LOG.warn("Could not read {}", file, e);
            return null;
        }
    }

    static void clearRestart()
    {
        try
        {
            Files.deleteIfExists(RESTART);
        }
        catch (IOException e)
        {
            LOG.warn("Could not delete {}", RESTART, e);
        }
    }
}
