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
import java.util.Locale;
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
            + "only a JSON object, no other text: {\"action\": one of \"play\", \"radio\", \"mix\", \"skip\", \"pause\", "
            + "\"resume\", \"stop\", \"chat\"; \"query\": what to search for (play, radio and mix only, otherwise empty); "
            + "\"reply\": what to say out loud}. "
            + "Use play when they want a song, artist or album played or queued (query = what to search YouTube for), "
            + "radio for a live radio station or radio of a genre (query = station name or genre), mix for several "
            + "songs like a song or artist (query = that song or artist, empty for like what's playing), "
            + "skip to move to the next song, pause and resume to pause or continue, stop to stop the music and "
            + "clear the queue. A temporary halt (\"hang on\", \"stop it for a sec\", \"hold the music\") is pause; "
            + "use stop only when they want the music finished. Use chat for everything else, including questions "
            + "about the current song, which is given in the context. Only choose a music action when they clearly "
            + "ask for it; otherwise chat. For music actions keep reply to a few words, like \"Skipping.\" "
            + "People call you Gunnabot, bot, robot or clanker. Every message you see was said to you, though not "
            + "always by name; the recent channel conversation in the context shows what they're responding to.";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl, apiKey, model, systemPrompt;
    private final int maxTokens;

    public LlmClient(String baseUrl, String apiKey, String model, String persona, int maxTokens)
    {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.systemPrompt = persona + ACTIONS;
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

    /** What the LLM decided to do with an utterance. */
    public static class Decision
    {
        public final VoiceCommand.Type action;
        public final String query, reply;

        Decision(VoiceCommand.Type action, String query, String reply)
        {
            this.action = action;
            this.query = query;
            this.reply = reply;
        }
    }

    public class Conversation
    {
        private final Deque<JSONObject> history = new ArrayDeque<>();

        /**
         * Classifies what a speaker said and writes the reply, keeping both in the history.
         * @param context a line describing the player state ("Now playing: ...")
         */
        public synchronized Decision decide(String speaker, String text, String context) throws IOException, InterruptedException
        {
            JSONObject user = new JSONObject().put("role", "user").put("content", speaker + ": " + text);
            JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", systemPrompt));
            history.forEach(messages::put);
            messages.put(new JSONObject().put("role", "system").put("content", "Context: " + context));
            messages.put(user);

            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", maxTokens)
                    .put("response_format", new JSONObject().put("type", "json_object"))
                    .put("messages", messages);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    // OpenRouter attribution header; ignored by other providers
                    .header("X-Title", "Gunna bot")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200)
                throw new IOException("LLM failed: HTTP " + res.statusCode() + " " + res.body());
            JSONArray choices = new JSONObject(res.body()).optJSONArray("choices");
            String content = choices == null || choices.isEmpty() ? ""
                    : choices.getJSONObject(0).getJSONObject("message").optString("content", "").trim();
            Decision d = parseDecision(content);

            history.add(user);
            history.add(new JSONObject().put("role", "assistant").put("content", content));
            while (history.size() > HISTORY_MESSAGES)
                history.removeFirst();
            return d;
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
        String reply = o.optString("reply", "").trim();
        VoiceCommand.Type action;
        switch (o.optString("action", "chat").trim().toLowerCase(Locale.ROOT))
        {
            case "play":   action = query.isEmpty() ? VoiceCommand.Type.RESUME : VoiceCommand.Type.PLAY; break;
            case "radio":  action = query.isEmpty() ? VoiceCommand.Type.CHAT : VoiceCommand.Type.RADIO; break;
            case "mix":    action = VoiceCommand.Type.MIX; break;
            case "skip":   action = VoiceCommand.Type.SKIP; break;
            case "pause":  action = VoiceCommand.Type.PAUSE; break;
            case "resume": action = VoiceCommand.Type.RESUME; break;
            case "stop":   action = VoiceCommand.Type.STOP; break;
            default:       action = VoiceCommand.Type.CHAT;
        }
        return new Decision(action, query, reply);
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
