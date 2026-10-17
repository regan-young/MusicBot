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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Works on something the bot said it would look into, in the background:
 * an agent loop on an OpenAI-compatible API with tools. On OpenRouter, web
 * search and the date are server tools that OpenRouter runs itself; posting
 * to the Discord text channel is a function tool run here. The model thinks
 * (reasoning on: unlike a live reply, a few seconds don't matter) and ends
 * with what to say out loud when the bot comes back to the conversation.
 */
public class Researcher
{
    /** What the research came back with. */
    public static class Result
    {
        public final String say;
        public final List<String> sources;
        public final int searches, posts;
        public final double cost;

        Result(String say, List<String> sources, int searches, int posts, double cost)
        {
            this.say = say;
            this.sources = sources;
            this.searches = searches;
            this.posts = posts;
            this.cost = cost;
        }
    }

    private static final int MAX_STEPS = 4;
    private static final int POST_LIMIT = 1900; // Discord's limit is 2000
    // "I've posted the details", "dropped a link in the chat"...
    static final Pattern CLAIMS_POST = Pattern.compile("(?i)\\b(posted|dropped|popped|put|shared|sent)\\b[^.]*\\b(chat|channel)\\b");

    static final String INSTRUCTIONS = "\n\nEarlier in the voice chat, %s asked you something and you said you'd "
            + "look into it. Work on it now. Search the web when it needs current or factual information (one or "
            + "two searches are usually enough) and think it through properly. If links, a list, numbers or other "
            + "details would help, post them once in the Discord text channel with post_message: short, plain text "
            + "and links, no @mentions. Then reply with only what you'll say out loud when you come back to "
            + "the conversation: two to four short spoken sentences, starting the way a person does when they "
            + "return with an answer (\"%s, I found it.\", \"Okay, about the match last night:\"). No URLs, markdown "
            + "or lists in what you say; mention it if you posted something. If you couldn't find a good answer, "
            + "say so honestly rather than guessing.";

    private static final JSONArray TOOLS = new JSONArray()
            .put(new JSONObject().put("type", "openrouter:web_search").put("parameters", new JSONObject().put("max_results", 4)))
            .put(new JSONObject().put("type", "openrouter:datetime"))
            .put(new JSONObject().put("type", "function").put("function", new JSONObject()
                    .put("name", "post_message")
                    .put("description", "Post a message in the Discord text channel next to the voice chat, for "
                            + "links, lists, numbers or anything easier to read than to hear.")
                    .put("parameters", new JSONObject().put("type", "object")
                            .put("properties", new JSONObject().put("text", new JSONObject().put("type", "string")
                                    .put("description", "The message, plain text with links")))
                            .put("required", new JSONArray().put("text")))));

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService runner = Executors.newCachedThreadPool(r ->
    {
        Thread t = new Thread(r, "voice-research");
        t.setDaemon(true);
        return t;
    });
    private final String baseUrl, apiKey, model, persona;

    public Researcher(String baseUrl, String apiKey, String model, String persona)
    {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.persona = persona;
    }

    public String getModel()
    {
        return model;
    }

    /**
     * @param speaker who asked
     * @param task what to find out, as the chat LLM described it
     * @param context what they said and the recent conversation
     * @param post posts a message in the text channel and returns the tool result to give the model
     */
    public CompletableFuture<Result> research(String speaker, String task, String context, Function<String, String> post)
    {
        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                return run(speaker, task, context, post);
            }
            catch (IOException e)
            {
                throw new CompletionException(e);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, runner);
    }

    private Result run(String speaker, String task, String context, Function<String, String> post)
            throws IOException, InterruptedException
    {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", persona + String.format(INSTRUCTIONS, speaker, speaker)))
                .put(new JSONObject().put("role", "user").put("content", "Task: " + task + "\n\n" + context));
        Set<String> sources = new LinkedHashSet<>();
        int searches = 0, posts = 0;
        double cost = 0;
        for (int step = 0; step < MAX_STEPS; step++)
        {
            boolean last = step == MAX_STEPS - 1;
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("max_tokens", 4000)
                    // OpenRouter runs server tools (searches) inside the request; without a cap the
                    // model searched 10 times for one rugby score (68 s, $0.07)
                    .put("max_tool_calls", 4)
                    // some thinking; this isn't a live reply, but medium took ~60 s
                    .put("reasoning", new JSONObject().put("effort", "low"))
                    .put("tools", TOOLS);
            if (last)
                body.put("tool_choice", "none"); // out of steps: answer with what it has
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-Title", "Gunna bot")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200)
                throw new IOException("Research failed: HTTP " + res.statusCode() + " " + res.body());
            JSONObject json = new JSONObject(res.body());
            JSONObject usage = json.optJSONObject("usage");
            if (usage != null)
            {
                cost += usage.optDouble("cost", 0);
                JSONObject server = usage.optJSONObject("server_tool_use_details");
                if (server != null)
                    searches += server.optInt("web_search_requests", 0);
            }
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.isEmpty())
                throw new IOException("Research failed: no choices in " + res.body());
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            sources.addAll(citations(message));
            JSONArray calls = message.optJSONArray("tool_calls");
            if (calls == null || calls.isEmpty())
            {
                String say = LlmClient.forSpeech(message.optString("content", "").replaceAll("https?://\\S+", ""));
                // it sometimes says it posted when it ran out of tool calls first; make that true
                if (posts == 0 && CLAIMS_POST.matcher(say).find())
                {
                    StringBuilder sb = new StringBuilder(say);
                    if (!sources.isEmpty())
                        sb.append("\n\nSources:");
                    sources.stream().limit(3).forEach(url -> sb.append("\n").append(url));
                    post.apply(sb.toString());
                    posts++;
                }
                return new Result(say, new ArrayList<>(sources), searches, posts, cost);
            }
            // keep the assistant turn (minus its reasoning, which is large) and answer each call
            JSONObject turn = new JSONObject().put("role", "assistant").put("tool_calls", calls);
            if (!message.isNull("content"))
                turn.put("content", message.optString("content", ""));
            messages.put(turn);
            for (int i = 0; i < calls.length(); i++)
            {
                JSONObject call = calls.getJSONObject(i);
                JSONObject fn = call.optJSONObject("function");
                String output;
                if (fn != null && fn.optString("name").equals("post_message"))
                {
                    String text = new JSONObject(fn.optString("arguments", "{}")).optString("text", "").trim();
                    if (text.isEmpty())
                        output = "Nothing to post.";
                    else if (posts > 0)
                        // models tend to post the same thing again and again
                        output = "You've already posted in the channel; don't post again. Give your spoken answer now.";
                    else
                    {
                        output = post.apply(text.length() > POST_LIMIT ? text.substring(0, POST_LIMIT) + "…" : text);
                        posts++;
                    }
                }
                else
                    output = "Unknown tool.";
                messages.put(new JSONObject().put("role", "tool").put("tool_call_id", call.optString("id")).put("content", output));
            }
        }
        throw new IOException("Research took more than " + MAX_STEPS + " steps");
    }

    /** The URLs the model cited (web search results it used). */
    static List<String> citations(JSONObject message)
    {
        List<String> urls = new ArrayList<>();
        JSONArray annotations = message.optJSONArray("annotations");
        if (annotations == null)
            return urls;
        for (int i = 0; i < annotations.length(); i++)
        {
            JSONObject c = annotations.getJSONObject(i).optJSONObject("url_citation");
            if (c != null && !c.optString("url").isEmpty())
                urls.add(c.optString("url"));
        }
        return urls;
    }
}
