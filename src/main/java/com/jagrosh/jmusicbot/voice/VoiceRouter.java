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
 * Asks a small, fast LLM (Gemma 3 12B on OpenRouter in the homelab) two things
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

    /** Added to the prompt in age-restricted channels, where the bot has an explicit mode. */
    static final String MOOD = "\n\nThis channel is 18+, and the bot has an explicit mode that people switch on and "
            + "off like a setting; the Explicit line says whether it's on. Two more commands here:\n"
            + "- explicit_on: they ask for the mode itself to be switched on (\"turn explicit mode on\", \"go NSFW\"). "
            + "Asking for dirty talk or flirting is chat, not this\n"
            + "- explicit_off: while it's on, they want it off: explicit mode off, back to normal, or the bot to tone "
            + "it down, clean it up or stop being dirty (while explicit mode is on, \"tone it down\" means this, "
            + "not the volume)";

    /** Commands the router may pick. "chat" goes to the conversational LLM, "none" is ignored. */
    static final Set<String> COMMANDS = Set.of("play", "radio", "mix", "skip", "pause", "resume", "stop",
            "volume", "shuffle", "hush", "not_you", "leave", "explicit_on", "explicit_off", "chat", "none");

    static final String SYSTEM = "You route voice chat for Gunnabot, a music bot in a Discord voice channel with "
            + "several friends. Speech recognition is imperfect: the bot's name may come out as Gunnabot, Gunnerbot, "
            + "Annabot, Guttabot, bot, robot or clanker.\n\n"
            + "Read the recent conversation and decide about the LAST line only:\n"
            + "1. to_bot: is the speaker talking TO Gunnabot (asking it something, telling it to do something, or "
            + "answering what it just said)? Friends talking to each other, or talking ABOUT the bot (calling it she, "
            + "her, it or the bot), is false.\n"
            + "2. command: the bot command that fits, from this list:\n"
            + "- play: play or queue a song, artist, album or genre they name (but \"songs like X\" is mix). args: "
            + "what to search for. If they "
            + "leave the choice to the bot (\"something you like\", \"surprise me\", \"put some music on\"), use chat "
            + "instead, so the bot can pick\n"
            + "- radio: tune in to a live radio station, or radio of a genre. Use this for any named station (RNZ, ZM, "
            + "The Edge, The Rock, Radio Hauraki, George FM, BBC, Triple J, KEXP, ...) or anything said with radio, "
            + "station or FM. args: station name or genre\n"
            + "- mix: queue several songs similar to a song or artist (\"more like this\", \"songs like X\", \"a mix of "
            + "X\"). args: song or artist, or empty for like what's playing\n"
            + "- skip: skip the current song\n"
            + "- pause: pause the music for now\n"
            + "- resume: continue paused music\n"
            + "- stop: stop the music and clear the queue (the bot stays in the channel)\n"
            + "- leave: the bot should leave the voice channel (\"bot, you can go\", \"gunnabot, disconnect\", "
            + "\"get out of here, clanker\"). Only when they tell the bot to leave; someone saying they're leaving "
            + "themselves is none\n"
            + "- volume: change the volume. args: the new number 0-150. The current volume is on the Player line; "
            + "\"up a bit\" is about 20 more, \"down a bit\" about 20 less\n"
            + "- shuffle: shuffle the queue\n"
            + "- hush: the bot should stop talking (\"shut up\", \"that's enough\", \"stop talking\"), not the music\n"
            + "- not_you: they're telling the bot it wasn't being spoken to (\"not you, bot\", \"I wasn't talking to you\", "
            + "\"nobody asked you, clanker\")\n"
            + "- chat: anything else said to the bot: questions (including about the current song), banter, "
            + "answers to what it asked, requests it can't do as a command\n"
            + "- none: not talking to the bot\n"
            + "Use stop only when someone clearly asks for the music to stop.\n\n"
            + "Reply with only JSON: {\"to_bot\": true or false, \"command\": \"...\", \"args\": \"...\"}";

    // a few worked examples steer a small model far better than more instructions
    private static final String[][] EXAMPLES = {
        { "Sam: did you watch the game last night", "{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}" },
        { "Regan: bot, where's that meme from\nGunnabot: It's from John Cena's 2021 video about ice cream.\nKFC: holy shit",
          "{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}" },
        { "Regan: robot, put on some Fleetwood Mac", "{\"to_bot\": true, \"command\": \"play\", \"args\": \"Fleetwood Mac\"}" },
        { "Regan: bot, chuck on the BBC World Service", "{\"to_bot\": true, \"command\": \"radio\", \"args\": \"BBC World Service\"}" },
        { "Regan: robot, can you do more songs like this one", "{\"to_bot\": true, \"command\": \"mix\", \"args\": \"\"}" },
        { "Sam: bot, play something you think we'd like", "{\"to_bot\": true, \"command\": \"chat\", \"args\": \"\"}" },
        { "Sam: Gunnabot what's the capital of Peru\nGunnabot: Lima, the city of kings.\nKFC: haha she knows everything",
          "{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}" },
        { "Regan: bot, do you know any good podcasts?\nGunnabot: A few. Do you want history or comedy?\nRegan: history please",
          "{\"to_bot\": true, \"command\": \"chat\", \"args\": \"\"}" },
    };
    private static final String NOT_SPOKEN = "(Gunnabot has not spoken recently.)";
    private static final String JUST_SPOKE = "(Gunnabot replied to this speaker a few seconds ago.)";

    // HTTP/1.1: plain-http local servers choke on the HTTP/2 upgrade request
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3)).build();
    private final String baseUrl, apiKey, model;
    private final boolean openRouter;

    /**
     * @param baseUrl an OpenAI-compatible API root, e.g. https://openrouter.ai/api or http://host:8001
     * @param apiKey bearer token, or empty for a local server
     */
    public VoiceRouter(String baseUrl, String apiKey, String model)
    {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.openRouter = this.baseUrl.contains("openrouter.ai");
    }

    /**
     * @param conversation recent lines as "Name: text", oldest first, ending with the line to route
     * @param botJustSpoke whether the bot said something in the last few seconds
     * @param player the player state ("Now playing X. Volume 100."), used for volume and nowplaying
     */
    public Route route(List<String> conversation, boolean botJustSpoke, String player) throws IOException, InterruptedException
    {
        return route(conversation, botJustSpoke, player, null);
    }

    /**
     * @param explicit in an 18+ channel, whether explicit mode is on, so the
     *                 router can judge the mood and switch it; null elsewhere
     */
    public Route route(List<String> conversation, boolean botJustSpoke, String player, Boolean explicit)
            throws IOException, InterruptedException
    {
        JSONArray messages = new JSONArray().put(message("system", explicit != null ? SYSTEM + MOOD : SYSTEM));
        for (String[] ex : EXAMPLES)
        {
            messages.put(message("user", "Recent conversation:\n" + ex[0] + "\n" + NOT_SPOKEN + "\nPlayer: Volume 100."));
            messages.put(message("assistant", ex[1]));
        }
        // the player state goes after the conversation, apart from it: inside
        // it, small models read follow-ups as questions about the song
        messages.put(message("user", "Recent conversation:\n" + String.join("\n", conversation) + "\n"
                + (botJustSpoke ? JUST_SPOKE : NOT_SPOKEN) + "\nPlayer: " + player
                + (explicit == null ? "" : "\nExplicit: " + (explicit ? "on" : "off"))));
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", 64)
                .put("temperature", 0);
        if (openRouter)
        {
            // a yes/no plus a command doesn't need thinking, and the fastest
            // host matters more than the cheapest for a live conversation
            body.put("reasoning", new JSONObject().put("enabled", false));
            body.put("provider", new JSONObject().put("sort", "latency"));
        }
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!apiKey.isEmpty())
            req.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> res;
        try
        {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e)
        {
            // the server may have dropped an idle keep-alive connection; retry once
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
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
