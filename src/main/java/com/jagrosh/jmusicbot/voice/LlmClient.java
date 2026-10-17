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
import java.util.ArrayDeque;
import java.util.Deque;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Chat client for an OpenAI-compatible completions API (OpenRouter by
 * default). Each {@link Conversation} keeps the last few turns so the bot
 * can follow a back-and-forth in the voice channel.
 */
public class LlmClient
{
    private static final int HISTORY_MESSAGES = 16;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl, apiKey, model, systemPrompt;
    private final int maxTokens;

    public LlmClient(String baseUrl, String apiKey, String model, String systemPrompt, int maxTokens)
    {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
    }

    public String getModel()
    {
        return model;
    }

    public Conversation newConversation()
    {
        return new Conversation();
    }

    public class Conversation
    {
        private final Deque<JSONObject> history = new ArrayDeque<>();

        /** Sends what a speaker said and returns the reply, keeping both in the history. */
        public synchronized String reply(String speaker, String text) throws IOException, InterruptedException
        {
            JSONObject user = new JSONObject().put("role", "user").put("content", speaker + ": " + text);
            JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", systemPrompt));
            history.forEach(messages::put);
            messages.put(user);

            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", maxTokens)
                    .put("messages", messages);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    // OpenRouter attribution headers; ignored by other providers
                    .header("X-Title", "Gunna bot")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200)
                throw new IOException("LLM failed: HTTP " + res.statusCode() + " " + res.body());
            JSONArray choices = new JSONObject(res.body()).optJSONArray("choices");
            String answer = choices == null || choices.isEmpty() ? ""
                    : choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            if (answer.isEmpty())
                return "";

            history.add(user);
            history.add(new JSONObject().put("role", "assistant").put("content", answer));
            while (history.size() > HISTORY_MESSAGES)
                history.removeFirst();
            return answer;
        }
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
