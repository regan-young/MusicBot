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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Asks a small, fast local LLM (Qwen3 1.7B on the homelab MacBook) two things
 * about each line of voice chat: is it said to the bot, and which bot command
 * does it map to. That lets people talk naturally ("yeah go on then" right
 * after the bot asked something) instead of saying the bot's name every
 * time, and runs music requests without a round trip to the cloud LLM.
 */
public class VoiceRouter
{
    /** A routed line: whether it's for the bot, and the command with its argument. */
    public static class Route
    {
        public final boolean toBot;
        public final String command, args;

        Route(boolean toBot, String command, String args)
        {
            this.toBot = toBot;
            this.command = command;
            this.args = args;
        }
    }

    /** Commands the router may pick. "chat" goes to the conversational LLM, "none" is ignored. */
    static final Set<String> COMMANDS = Set.of("play", "radio", "mix", "skip", "pause", "resume", "stop",
            "volume", "shuffle", "chat", "none");

    static final String SYSTEM = "You route voice chat for Gunnabot, a music bot in a Discord voice channel with "
            + "several friends. Speech recognition is imperfect: the bot's name may come out as Gunnabot, Gunnerbot, "
            + "Annabot, Guttabot, bot, robot or clanker.\n\n"
            + "Read the recent conversation and decide about the LAST line only:\n"
            + "1. to_bot: is the speaker talking TO Gunnabot (asking it something, telling it to do something, or "
            + "answering what it just said)? Friends talking to each other, or talking ABOUT the bot (calling it she, "
            + "her, it or the bot), is false.\n"
            + "2. command: the bot command that fits, from this list:\n"
            + "- play: play or queue a specific song, artist or album. args: what to search for\n"
            + "- radio: tune in to a live radio station, or radio of a genre. Use this for any named station (RNZ, ZM, "
            + "The Edge, The Rock, Radio Hauraki, George FM, BBC, Triple J, KEXP, ...) or anything said with radio, "
            + "station or FM. args: station name or genre\n"
            + "- mix: queue several songs similar to a song or artist (\"more like this\", \"songs like X\", \"a mix of "
            + "X\"). args: song or artist, or empty for like what's playing\n"
            + "- skip: skip the current song\n"
            + "- pause: pause the music for now\n"
            + "- resume: continue paused music\n"
            + "- stop: stop the music and clear the queue\n"
            + "- volume: change the volume. args: the new number 0-150. The current volume is on the Player line; "
            + "\"up a bit\" is about 20 more, \"down a bit\" about 20 less\n"
            + "- shuffle: shuffle the queue\n"
            + "- chat: anything else said to the bot: questions (including about the current song), banter, "
            + "answers to what it asked, requests it can't do as a command\n"
            + "- none: not talking to the bot\n\n"
            + "Reply with only JSON: {\"to_bot\": true or false, \"command\": \"...\", \"args\": \"...\"}";

    // a few worked examples steer a small model far better than more instructions
    private static final String[][] EXAMPLES = {
        { "Sam: did you watch the game last night", "{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}" },
        { "Regan: robot, put on some Fleetwood Mac", "{\"to_bot\": true, \"command\": \"play\", \"args\": \"Fleetwood Mac\"}" },
        { "Regan: bot, chuck on the BBC World Service", "{\"to_bot\": true, \"command\": \"radio\", \"args\": \"BBC World Service\"}" },
        { "Regan: robot, can you do more songs like this one", "{\"to_bot\": true, \"command\": \"mix\", \"args\": \"\"}" },
        { "Sam: Gunnabot what's the capital of Peru\nGunnabot: Lima, the city of kings.\nKFC: haha she knows everything",
          "{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}" },
        { "Regan: bot, do you know any good podcasts?\nGunnabot: A few. Do you want history or comedy?\nRegan: history please",
          "{\"to_bot\": true, \"command\": \"chat\", \"args\": \"\"}" },
    };
    private static final String NOT_SPOKEN = "(Gunnabot has not spoken recently.)";
    private static final String JUST_SPOKE = "(Gunnabot spoke a few seconds ago.)";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl, model;

    public VoiceRouter(String baseUrl, String model)
    {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.model = model;
    }

    /**
     * @param conversation recent lines as "Name: text", oldest first, ending with the line to route
     * @param botJustSpoke whether the bot said something in the last few seconds
     * @param player the player state ("Now playing X. Volume 100."), used for volume and nowplaying
     */
    public Route route(List<String> conversation, boolean botJustSpoke, String player) throws IOException, InterruptedException
    {
        JSONArray messages = new JSONArray().put(message("system", SYSTEM));
        for (String[] ex : EXAMPLES)
        {
            messages.put(message("user", "Recent conversation:\n" + ex[0] + "\n" + NOT_SPOKEN + "\nPlayer: Volume 100."));
            messages.put(message("assistant", ex[1]));
        }
        // the player state goes after the conversation, apart from it: inside
        // it, small models read follow-ups as questions about the song
        messages.put(message("user", "Recent conversation:\n" + String.join("\n", conversation) + "\n"
                + (botJustSpoke ? JUST_SPOKE : NOT_SPOKEN) + "\nPlayer: " + player));
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", 48)
                .put("temperature", 0);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res;
        try
        {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e)
        {
            // the server may have dropped an idle keep-alive connection; retry once
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        if (res.statusCode() != 200)
            throw new IOException("Router failed: HTTP " + res.statusCode());
        JSONArray choices = new JSONObject(res.body()).optJSONArray("choices");
        String content = choices == null || choices.isEmpty() ? ""
                : choices.getJSONObject(0).getJSONObject("message").optString("content", "");
        return parse(content);
    }

    private static JSONObject message(String role, String content)
    {
        return new JSONObject().put("role", role).put("content", content);
    }

    /** Parses the router's JSON; anything unusable counts as not for the bot. */
    static Route parse(String content)
    {
        // Qwen3 may emit an empty <think></think> block before the answer
        String c = content.replaceAll("(?s)<think>.*?</think>", "");
        int start = c.indexOf('{'), end = c.lastIndexOf('}');
        if (start < 0 || end <= start)
            return new Route(false, "none", "");
        try
        {
            JSONObject o = new JSONObject(c.substring(start, end + 1));
            String command = o.optString("command", "none").trim().toLowerCase(Locale.ROOT);
            if (!COMMANDS.contains(command))
                command = "chat";
            boolean toBot = o.optBoolean("to_bot", false) && !command.equals("none");
            return new Route(toBot, toBot ? command : "none", o.optString("args", "").trim());
        }
        catch (JSONException e)
        {
            return new Route(false, "none", "");
        }
    }

    /** Short spoken confirmations for commands whose own replies are embeds or edited messages. */
    static final Map<String, String> ACKS = Map.of(
            "play", "Queuing up %s.",
            "radio", "Tuning in to %s.",
            "mix", "Putting a mix together.",
            "skip", "Skipping.",
            "stop", "Stopping the music.",
            "volume", "Volume %s.",
            "shuffle", "Shuffled.");
}
