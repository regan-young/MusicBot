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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks a small, fast model two things about each line of voice chat: is it
 * said to the bot, and which bot command does it map to. That lets people talk
 * naturally ("yeah go on then" right after the bot asked something) instead of
 * saying the bot's name every time, and runs music requests without a round
 * trip to the cloud LLM.
 *
 * There are two ways to ask. A decision model (Gemini Decision 1 through
 * OpenRouter's Decisions API) answers both questions with probabilities and
 * writes no text, so it's quick and says how sure it is; the chat model
 * (Gemma 3 12B) is then only asked for what a command needs spelled out, the
 * song or the volume. Without a decision model, or when it fails, the chat
 * model answers everything as JSON, as it did before.
 */
public class VoiceRouter
{
    private static final Logger LOG = LoggerFactory.getLogger(VoiceRouter.class);

    /** A routed line: whether it's for the bot, and the command with its argument. */
    public static class Route
    {
        public final boolean toBot;
        public final String command, args;
        /** How likely the line is for the bot, 0 to 1; just 1 or 0 from a model that only says yes or no. */
        public final double confidence;
        /** Whether the confidence is a real probability, which can outweigh hearing the bot's name. */
        public final boolean scored;

        Route(boolean toBot, String command, String args)
        {
            this(toBot, command, args, toBot ? 1 : 0, false);
        }

        Route(boolean toBot, String command, String args, double confidence, boolean scored)
        {
            this.toBot = toBot;
            this.command = command;
            this.args = args;
            this.confidence = confidence;
            this.scored = scored;
        }
    }

    // How sure a decision model must be before the bot acts on a line. Butting
    // in on friends is worse than being asked twice, so a line with nothing
    // pointing at the bot needs more. On two evenings of real lines, ones for
    // the bot scored 0.53 and up and the rest 0.43 and down.
    static final double FOR_BOT = 0.5, FOR_BOT_UNPROMPTED = 0.6;

    /**
     * Whether to act on a line.
     * @param route the router's answer, or null if it couldn't be asked
     * @param named whether the line has one of the bot's names in it
     * @param followUp whether the bot answered this speaker a moment ago
     */
    static boolean forBot(Route route, boolean named, boolean followUp)
    {
        if (route == null)
            return named;
        // a yes or no can't be weighed against the name, so the name wins. A
        // probability can: "are you still going bot?" is about a Dota lane
        if (!route.scored)
            return named || route.toBot;
        return route.confidence >= (named || followUp ? FOR_BOT : FOR_BOT_UNPROMPTED);
    }

    /** Added to the prompt in age-restricted channels, where the bot has an explicit mode. */
    static final String MOOD = "\n\nThis channel is 18+, and the bot has an explicit mode that people switch on and "
            + "off like a setting; the Explicit line says whether it's on. Two more commands here:\n"
            + "- explicit_on: they ask for the mode itself to be switched on (\"turn explicit mode on\", \"go NSFW\"). "
            + "Asking for dirty talk or flirting is chat, not this\n"
            + "- explicit_off: while it's on, they want it off: explicit mode off, back to normal, or the bot to tone "
            + "it down, clean it up or stop being dirty (while explicit mode is on, \"tone it down\" means this, "
            + "not the volume)";

    /** Added to the prompt wherever crude mode is available. */
    static final String CRUDE = "\n\nThe bot has a crude mode, where it swears and roasts people, that people switch on "
            + "and off like a setting; the Crude line says whether it's on. Two more commands here:\n"
            + "- crude_on: they ask for the mode itself to be switched on (\"turn crude mode on\", \"go crude\", "
            + "\"start swearing at us\", \"stop holding back\"), and only when the Crude line says off. Asking for one "
            + "roast or insult is chat, not this\n"
            + "- crude_off: only when the Crude line says on: they want it off: crude mode off, back to normal, or the "
            + "bot to be nice, stop swearing or tone it down (\"tone it down\" means this only while Crude is on; "
            + "when Crude is off it's the volume, about 20 less)";

    /** Commands the router may pick. "chat" goes to the conversational LLM, "none" is ignored. */
    static final Set<String> COMMANDS = Set.of("play", "radio", "mix", "skip", "skipto", "pause", "resume", "stop",
            "volume", "shuffle", "repeat", "hush", "not_you", "leave", "explicit_on", "explicit_off", "crude_on", "crude_off", "chat", "none");

    static final String SYSTEM = "You route voice chat for Gunnabot, a music bot in a Discord voice channel with "
            + "several friends. Speech recognition is imperfect: the bot's name may come out as Gunnabot, Gunnerbot, "
            + "Annabot, Guttabot, bot, robot or clanker.\n\n"
            + "Read the recent conversation and decide about the LAST line only. Earlier lines are just context: never "
            + "repeat a command because someone asked for it earlier or the bot just did it. If the LAST line doesn't "
            + "itself ask for a command, it's chat or none:\n"
            + "1. to_bot: is the speaker talking TO Gunnabot (asking it something, telling it to do something, or "
            + "answering what it just said)? Friends talking to each other, or talking ABOUT the bot (calling it she, "
            + "her, it or the bot), is false.\n"
            + "2. command: the bot command that fits, from this list:\n"
            + "- play: play or queue a song, artist, album or genre they name (but \"songs like X\" is mix). args: "
            + "what to search for. If they "
            + "leave the choice to the bot (\"something you like\", \"surprise me\", \"put some music on\"), or ask by "
            + "mood or loose description (\"something heavy\", \"chill vibes\", \"sad songs\"), use chat "
            + "instead, so the bot can pick a fitting song. A song that played earlier (\"play that again\", \"the last "
            + "song\", \"the one before\") is chat too, so the bot can look it up\n"
            + "- radio: tune in to a live radio station, or radio of a genre. Use this for any named station (RNZ, ZM, "
            + "The Edge, The Rock, Radio Hauraki, George FM, BBC, Triple J, KEXP, ...) or anything said with radio, "
            + "station or FM. args: station name or genre\n"
            + "- mix: queue several songs similar to a song or artist (\"more like this\", \"songs like X\", \"a mix of "
            + "X\"). args: song or artist, or empty for like what's playing\n"
            + "- skip: skip the current song (\"skip to the next one\" is this too)\n"
            + "- skipto: jump to a song already in the queue (\"skip to Dreams\", \"jump to the Chappell Roan one\", "
            + "\"go to number three\"). args: the song's name, or its number in the queue as digits. Moving within the "
            + "current song (\"thirty seconds in\") isn't this\n"
            + "- pause: pause the music for now\n"
            + "- resume: continue paused music\n"
            + "- stop: stop the music and clear the queue (the bot stays in the channel)\n"
            + "- leave: the bot should leave the voice channel (\"bot, you can go\", \"gunnabot, disconnect\", "
            + "\"get out of here, clanker\"). Only when they tell the bot to leave; someone saying they're leaving "
            + "themselves is none\n"
            + "- volume: change the volume. args: the new number 0-150. The current volume is on the Player line; "
            + "\"up a bit\" is about 20 more, \"down a bit\" about 20 less\n"
            + "- shuffle: shuffle the queue\n"
            + "- repeat: toggle repeat (\"repeat\", \"repeat this song\", \"play on repeat\", \"loop the queue\")\n"
            + "- hush: the bot should stop talking right now (\"shut up\", \"that's enough\", \"stop talking\"), not the "
            + "music. Asking it to change how it talks or behaves from now on is chat\n"
            + "- not_you: they're telling the bot it wasn't being spoken to (\"not you, bot\", \"I wasn't talking to you\", "
            + "\"nobody asked you, clanker\")\n"
            + "- chat: anything else said to the bot: questions (including about the current song), banter, "
            + "answers to what it asked, requests it can't do as a command, and any request to change the bot's own "
            + "code, features or behaviour, even when it mentions music, pausing or talking\n"
            + "- none: not talking to the bot\n"
            + "Use stop only when someone clearly asks for the music to stop.\n\n"
            + "Reply with only JSON: {\"to_bot\": true or false, \"command\": \"...\", \"args\": \"...\"}";

    // a few worked examples steer a small model far better than more instructions
    private static final String[][] EXAMPLES = {
        { "Sam: did you watch the game last night", "{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}" },
        { "Regan: bot, volume thirty\nGunnabot: Volume 30.\nKFC: thousand", "{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}" },
        { "Sam: bot, play Dreams\nGunnabot: Queuing up Dreams.\nKFC: back in a second", "{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}" },
        { "Regan: bot, where's that meme from\nGunnabot: It's from John Cena's 2021 video about ice cream.\nKFC: holy shit",
          "{\"to_bot\": false, \"command\": \"none\", \"args\": \"\"}" },
        { "Regan: robot, put on some Fleetwood Mac", "{\"to_bot\": true, \"command\": \"play\", \"args\": \"Fleetwood Mac\"}" },
        { "Sam: bot, play that last song again", "{\"to_bot\": true, \"command\": \"chat\", \"args\": \"\"}" },
        { "Sam: bot, put this song on repeat", "{\"to_bot\": true, \"command\": \"repeat\", \"args\": \"\"}" },
        { "Regan: gunnabot, change your code so you don't pause the music when you speak", "{\"to_bot\": true, \"command\": \"chat\", \"args\": \"\"}" },
        { "Sam: bot, skip to the Fleetwood Mac one", "{\"to_bot\": true, \"command\": \"skipto\", \"args\": \"Fleetwood Mac\"}" },
        { "Regan: bot, chuck on the BBC World Service", "{\"to_bot\": true, \"command\": \"radio\", \"args\": \"BBC World Service\"}" },
        { "Regan: robot, can you do more songs like this one", "{\"to_bot\": true, \"command\": \"mix\", \"args\": \"\"}" },
        { "Sam: bot, play something you think we'd like", "{\"to_bot\": true, \"command\": \"chat\", \"args\": \"\"}" },
        { "Sam: bot, play something heavy", "{\"to_bot\": true, \"command\": \"chat\", \"args\": \"\"}" },
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
    // a normal answer takes about a second; past this one is probably stuck
    private static final long HEDGE_AFTER_MS = 2000;
    // the decision model normally answers in 0.7 s
    private static final long DECISION_HEDGE_AFTER_MS = 1200;
    private final String baseUrl, apiKey, model, decisionModel;
    private final boolean openRouter;

    /**
     * @param baseUrl an OpenAI-compatible API root, e.g. https://openrouter.ai/api or http://host:8001
     * @param apiKey bearer token, or empty for a local server
     */
    public VoiceRouter(String baseUrl, String apiKey, String model)
    {
        this(baseUrl, apiKey, model, null);
    }

    /**
     * @param decisionModel a model on OpenRouter's Decisions API to make the
     *                      choices, leaving the chat model the song names and
     *                      volumes; null or empty for the chat model alone
     */
    public VoiceRouter(String baseUrl, String apiKey, String model, String decisionModel)
    {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.openRouter = this.baseUrl.contains("openrouter.ai");
        // the Decisions API is OpenRouter's own
        this.decisionModel = decisionModel == null || decisionModel.isBlank() || !openRouter ? null : decisionModel.trim();
    }

    /** Which models route, for the log. */
    public String describe()
    {
        return decisionModel == null ? model : decisionModel + ", with " + model + " for song names and volumes";
    }

    /**
     * @param conversation recent lines as "Name: text", oldest first, ending with the line to route
     * @param botJustSpoke whether the bot said something in the last few seconds
     * @param player the player state ("Now playing X. Volume 100."), used for volume and nowplaying
     */
    public Route route(List<String> conversation, boolean botJustSpoke, String player) throws IOException, InterruptedException
    {
        return route(conversation, botJustSpoke, player, null, null);
    }

    private static final Pattern ASKS_FOR_CRUDE = Pattern.compile(
            "\\b(?:crude|crud|cruz|crewed|rude|swear\\w*|cuss\\w*|curs(?:e|ing)|foul|potty|hold(?:ing)? back)\\b");

    private static final Pattern SKIP_WORDS = Pattern.compile("\\b(?:skip(?:ping)?|jump|go(?: back)? to|move to|switch to|change to)\\b");
    // "skip thirty seconds into this song" is moving within the song, not to another one
    private static final Pattern DURATION = Pattern.compile("(?i)\\b(?:\\d+|thirty|forty|fifty|sixty|ten|twenty)\\s*(?:sec|secs|seconds?|min|mins|minutes?)\\b");
    private static final Set<String> COMMON_WORDS = Set.of("the", "and", "some", "song", "songs", "music", "for", "you", "with", "from", "that", "this");
    private static final Set<String> MUSIC_WITH_ARGS = Set.of("play", "radio", "mix", "skipto");
    private static final long SAME_COMMAND_MS = 60_000;

    /**
     * Whether a command is a repeat rather than a request. Small models tend to
     * pick the last command again for whatever's said next ("Back second" came
     * back as crude_on, "Thousand" as the volume just set, "I'm Jim. No." as the
     * song just queued), so one that would change nothing wasn't what they
     * meant, crude mode only goes on from a line that asks for it, and a music
     * command needs a line with something to do with it.
     * @param explicit whether explicit mode is on, or null if it isn't available
     * @param crude whether crude mode is on, or null if it isn't available
     * @param volume the volume now
     */
    static boolean isRepeat(String command, String args, String said, Boolean explicit, Boolean crude, int volume)
    {
        switch (command)
        {
            case "crude_on":
                return Boolean.TRUE.equals(crude) || !ASKS_FOR_CRUDE.matcher(said.toLowerCase(Locale.ROOT)).find();
            case "crude_off":
                return !Boolean.TRUE.equals(crude);
            case "explicit_on":
                return Boolean.TRUE.equals(explicit);
            case "explicit_off":
                return !Boolean.TRUE.equals(explicit);
            case "volume":
                return args.equals(String.valueOf(volume));
            case "skipto":
                return !SKIP_WORDS.matcher(said.toLowerCase(Locale.ROOT)).find() || DURATION.matcher(args + " " + said).find();
            case "play":
            case "radio":
            case "mix":
                return !sharesAWord(args, said);
            default:
                return false;
        }
    }

    /**
     * Whether a music command just ran with the same song. Only worth asking
     * about a line that didn't name the bot: "I thought this was Machine Gun
     * Kelly" has the right words, straight after someone asked for him.
     */
    static boolean sameAsLast(String command, String args, String lastCommand, String lastArgs, long sinceMs)
    {
        return MUSIC_WITH_ARGS.contains(command) && command.equals(lastCommand) && args.equalsIgnoreCase(lastArgs)
                && sinceMs < SAME_COMMAND_MS;
    }

    /** Whether the line has a word of what the command would search for; nothing to check means yes. */
    private static boolean sharesAWord(String args, String said)
    {
        Set<String> heard = words(said);
        Set<String> wanted = words(args);
        return wanted.isEmpty() || wanted.stream().anyMatch(heard::contains);
    }

    private static Set<String> words(String s)
    {
        return Arrays.stream(s.toLowerCase(Locale.ROOT).replace("'", "").split("[^\\p{L}\\p{N}]+"))
                .filter(w -> w.length() >= 3 && !COMMON_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    /** The prompt, with the commands for whichever modes are available here. */
    static String system(Boolean explicit, Boolean crude)
    {
        return SYSTEM + (explicit != null ? MOOD : "") + (crude != null ? CRUDE : "");
    }

    /**
     * @param explicit in an 18+ channel, whether explicit mode is on, so the
     *                 router can judge the mood and switch it; null elsewhere
     * @param crude whether crude mode is on, for the same reason; null if it isn't available
     */
    public Route route(List<String> conversation, boolean botJustSpoke, String player, Boolean explicit, Boolean crude)
            throws IOException, InterruptedException
    {
        return route(conversation, botJustSpoke, player, explicit, crude, true);
    }

    /**
     * Routes a line for the command alone, without what it needs spelled out:
     * enough to see a mode being switched, and one request fewer.
     */
    public Route command(List<String> conversation, boolean botJustSpoke, String player, Boolean explicit, Boolean crude)
            throws IOException, InterruptedException
    {
        return route(conversation, botJustSpoke, player, explicit, crude, false);
    }

    private Route route(List<String> conversation, boolean botJustSpoke, String player, Boolean explicit, Boolean crude,
            boolean withArgs) throws IOException, InterruptedException
    {
        if (decisionModel != null)
        {
            try
            {
                return decide(conversation, botJustSpoke, player, explicit, crude, withArgs);
            }
            catch (IOException | JSONException e)
            {
                // the Decisions API is in alpha: carry on with the chat model
                LOG.warn("{} couldn't route a line, asking {}: {}", decisionModel, model, e.toString());
            }
        }
        return ask(conversation, botJustSpoke, player, explicit, crude);
    }

    /** Routes a line with the chat model alone, which answers in JSON. */
    Route ask(List<String> conversation, boolean botJustSpoke, String player, Boolean explicit, Boolean crude)
            throws IOException, InterruptedException
    {
        JSONArray messages = new JSONArray().put(message("system", system(explicit, crude)));
        for (String[] ex : EXAMPLES)
        {
            messages.put(message("user", "Recent conversation:\n" + ex[0] + "\n" + NOT_SPOKEN + "\nPlayer: Volume 100."));
            messages.put(message("assistant", ex[1]));
        }
        // the player state goes after the conversation, apart from it: inside
        // it, small models read follow-ups as questions about the song
        messages.put(message("user", "Recent conversation:\n" + String.join("\n", conversation) + "\n"
                + (botJustSpoke ? JUST_SPOKE : NOT_SPOKEN) + "\nPlayer: " + player
                + (explicit == null ? "" : "\nExplicit: " + (explicit ? "on" : "off"))
                + (crude == null ? "" : "\nCrude: " + (crude ? "on" : "off"))));
        return parse(complete(messages, 64));
    }

    /** Sends a chat request to the chat model and returns what it wrote. */
    private String complete(JSONArray messages, int maxTokens) throws IOException, InterruptedException
    {
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", maxTokens)
                .put("temperature", 0);
        if (openRouter)
        {
            // a yes/no plus a command doesn't need thinking, and the fastest
            // host matters more than the cheapest for a live conversation
            body.put("reasoning", new JSONObject().put("enabled", false));
            body.put("provider", new JSONObject().put("sort", "latency"));
        }
        JSONObject res = post("/v1/chat/completions", body.toString(), 10, HEDGE_AFTER_MS);
        JSONArray choices = res.optJSONArray("choices");
        return choices == null || choices.isEmpty() ? ""
                : choices.getJSONObject(0).getJSONObject("message").optString("content", "");
    }

    private JSONObject post(String path, String body, int timeoutSeconds, long hedgeAfterMs) throws IOException, InterruptedException
    {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!apiKey.isEmpty())
            req.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> res = send(req.build(), hedgeAfterMs);
        if (res.statusCode() != 200)
            throw new IOException("Router failed: HTTP " + res.statusCode());
        return new JSONObject(res.body());
    }

    // The decision model's questions. It gets no worked examples, so what
    // these say is all it knows about the bot and the people talking.
    private static final String TO_BOT = "This is voice chat between friends in a Discord channel, usually while they "
            + "play Dota 2 together. Gunnabot is the music bot in the channel, and she can chat too. Speech recognition is "
            + "imperfect: her name may come out as Gunnerbot, Gunabot, Ganabot, Annabot, gonna bot, bot, robot or clanker. "
            + "Is the speaker of last_line talking TO Gunnabot? Judge last_line only; earlier_lines are context.";
    private static final String TO_BOT_TRUE = "They ask or tell Gunnabot something, or answer what she just said to them. "
            + "bot_just_answered_this_speaker makes a reply to her more likely, but only when the line makes sense as one.";
    private static final String TO_BOT_FALSE = "They are talking to their friends, reacting to something, or talking ABOUT "
            + "the bot (she, her, it, the bot, 'why did Gunnabot say that'). Game talk is not for her: in Dota 'bot' is the "
            + "bottom lane ('going bot', 'bot tower'), and pause, stop, skip and play are often about the game. A line that "
            + "only continues the friends' own conversation is false even straight after she spoke.";
    private static final String WHICH = "Which of Gunnabot's commands does last_line itself ask for? Earlier lines are only "
            + "context: never pick a command because someone asked for it earlier or the bot just did it. An answer to "
            + "something the bot asked ('yes', 'go on then', 'the first one') is chat.";
    private static final String[][] CHOICES = {
        { "play", "Play or queue a song, artist or album they name." },
        { "radio", "Tune in to a live radio station (RNZ, ZM, The Edge, The Rock, George FM, BBC, Triple J, ...) or radio "
                + "of a genre; anything said with radio, station or FM." },
        { "mix", "Queue several songs similar to a song or artist ('more like this', 'songs like X', 'a mix of X')." },
        { "skip", "Skip the current song, or go to the next one." },
        { "skipto", "Jump to a particular song already in the queue, by name or number ('skip to Dreams', 'go to number "
                + "three')." },
        { "pause", "Pause the music for now." },
        { "resume", "Continue paused music." },
        { "stop", "Stop the music and clear the queue. Only when they clearly ask for the music to stop." },
        { "leave", "Tell the bot to leave the voice channel ('bot, you can go', 'disconnect'). Someone saying they are "
                + "leaving themselves is none." },
        { "volume", "Change the music volume: louder, quieter, or to a number. A question about the volume is chat." },
        { "shuffle", "Shuffle the queue." },
        { "repeat", "Toggle repeat or loop for the song or queue." },
        { "hush", "The bot should stop talking right now ('shut up', 'that's enough', 'stop talking'), not the music." },
        { "not_you", "Telling the bot it was not being spoken to ('not you, bot', 'I wasn't talking to you', 'nobody "
                + "asked you'). Putting the bot right about what they meant is chat." },
    };
    private static final String[][] EXPLICIT_CHOICES = {
        { "explicit_on", "Switch the bot's explicit mode on ('turn explicit mode on', 'go NSFW'), only when explicit_mode "
                + "is off. Asking for dirty talk or flirting is chat." },
        { "explicit_off", "Only when explicit_mode is on: switch it off, back to normal, tone it down or clean it up." },
    };
    private static final String[][] CRUDE_CHOICES = {
        { "crude_on", "Switch the bot's crude mode on ('turn crude mode on', 'go crude', 'start swearing at us'), only "
                + "when crude_mode is off. Asking for one roast or insult is chat." },
        { "crude_off", "Only when crude_mode is on: switch it off, be nice, stop swearing, or tone it down." },
    };
    // chat and none go last, after the modes
    private static final String[][] LAST_CHOICES = {
        { "chat", "Anything else said to the bot: questions (including about the current song or the volume), banter, "
                + "answers to what it asked, requests it can't do as a command, and any request to change the bot's own "
                + "code or behaviour. Also music requests that leave the choice to the bot or ask by mood ('something "
                + "chill', 'a banger', 'surprise me') or for a song played earlier ('play that again')." },
        { "none", "Not said to the bot at all." },
    };

    /** A JSON object whose keys stay in the order given, which JSONObject's don't. */
    private static JSONString ordered(Object... keysAndValues)
    {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keysAndValues.length; i += 2)
            sb.append(i == 0 ? "" : ",").append(JSONObject.quote((String) keysAndValues[i])).append(':')
              .append(JSONObject.valueToString(keysAndValues[i + 1]));
        String json = sb.append('}').toString();
        return () -> json;
    }

    /** The Decisions API request for a line. */
    String decisionRequest(List<String> conversation, boolean botJustSpoke, String player, Boolean explicit, Boolean crude)
    {
        List<Object> state = new ArrayList<>(List.of(
                "earlier_lines", new JSONArray(conversation.subList(0, conversation.size() - 1)),
                "last_line", conversation.get(conversation.size() - 1),
                "bot_just_answered_this_speaker", botJustSpoke,
                "player", player));
        List<Object> choices = new ArrayList<>();
        for (String[] c : CHOICES)
            choices.addAll(List.of(c[0], c[1]));
        if (explicit != null)
        {
            state.addAll(List.of("explicit_mode", explicit ? "on" : "off"));
            for (String[] c : EXPLICIT_CHOICES)
                choices.addAll(List.of(c[0], c[1]));
        }
        if (crude != null)
        {
            state.addAll(List.of("crude_mode", crude ? "on" : "off"));
            for (String[] c : CRUDE_CHOICES)
                choices.addAll(List.of(c[0], c[1]));
        }
        for (String[] c : LAST_CHOICES)
            choices.addAll(List.of(c[0], c[1]));
        return ordered(
                "model", decisionModel,
                "state", ordered(state.toArray()),
                "questions", ordered(
                        "to_bot", ordered("type", "noul", "instructions", TO_BOT,
                                "criteria", ordered("true", TO_BOT_TRUE, "false", TO_BOT_FALSE)),
                        "command", ordered("type", "choice", "instructions", WHICH,
                                "criteria", ordered(choices.toArray())))).toJSONString();
    }

    /**
     * Routes a line with the decision model, then asks the chat model for the
     * song or volume if the command needs one.
     */
    private Route decide(List<String> conversation, boolean botJustSpoke, String player, Boolean explicit, Boolean crude,
            boolean withArgs) throws IOException, InterruptedException
    {
        JSONObject res = post("/alpha/decisions", decisionRequest(conversation, botJustSpoke, player, explicit, crude),
                5, DECISION_HEDGE_AFTER_MS);
        Route route = parseDecision(res);
        if (!withArgs || !NEEDS_ARGS.contains(route.command) || route.confidence < FOR_BOT)
            return route;
        String args;
        try
        {
            args = checkArgs(route.command, args(route.command, conversation.get(conversation.size() - 1), player), player);
        }
        catch (IOException | JSONException e)
        {
            LOG.warn("{} couldn't say what to {}: {}", model, route.command, e.toString());
            args = null;
        }
        // without the song or the number, the chat LLM can still work out what they want
        return new Route(route.toBot, args == null ? "chat" : route.command, args == null ? "" : args, route.confidence, true);
    }

    // below this, the decision model is guessing at the command; talk it over instead
    private static final double SURE_OF_COMMAND = 0.5;

    /**
     * Reads a Decisions API answer. The model is asked twice over whether the
     * line is for the bot, once outright and once as the "none" command, and
     * the two err differently ("I wasn't talking to you" is plainly not_you,
     * and plainly not talking to the bot), so the confidence is their mean.
     */
    static Route parseDecision(JSONObject response)
    {
        JSONObject answers = response.getJSONObject("answers");
        double toBot = answers.getJSONObject("to_bot").getDouble("noul");
        JSONObject which = answers.getJSONObject("command");
        String command = which.optString("choice", "none").trim().toLowerCase(Locale.ROOT);
        JSONObject probabilities = which.optJSONObject("probabilities");
        double none = probabilities == null ? (command.equals("none") ? 1 : 0) : probabilities.optDouble("none", 0);
        double confidence = (toBot + 1 - none) / 2;
        if (!COMMANDS.contains(command) || command.equals("none") || which.optDouble("confidence", 1) < SURE_OF_COMMAND)
            command = "chat";
        return new Route(confidence >= FOR_BOT, command, "", confidence, true);
    }

    /** Commands that need something from the line: a song, a station or a number. */
    static final Set<String> NEEDS_ARGS = Set.of("play", "radio", "mix", "skipto", "volume");

    private static final String ARGS_SYSTEM = "Gunnabot is a music bot in a Discord voice channel. Someone just asked it "
            + "for the command below; work out what the command needs from their line. Speech recognition is imperfect, "
            + "and the bot's name (Gunnabot, Gunnerbot, bot, robot, clanker), filler and politeness aren't part of it.\n"
            + "- play: the song, artist or album to search for\n"
            + "- radio: the station name or genre\n"
            + "- mix: the song or artist to base the mix on, or empty for what's playing now\n"
            + "- skipto: the queued song's name, or its number in the queue as digits\n"
            + "- volume: a number from 0 to 150 to set it to (\"to N\", or a bare number), or else the change with "
            + "its sign: +20 or -20 for up or down, or a bit; +40 or -40 for a lot; +N or -N for \"by N\"\n"
            + "Reply with only JSON: {\"args\": \"...\"}";
    private static final String[][] ARGS_EXAMPLES = {
        { "play", "Regan: robot, put on some Fleetwood Mac", "Volume 100.", "Fleetwood Mac" },
        { "play", "Devon: Gunnerbot, play us uh Africa by Toto, mate.", "Volume 100.", "Africa by Toto" },
        { "radio", "Regan: bot, chuck on the BBC World Service", "Volume 100.", "BBC World Service" },
        { "mix", "Regan: robot, can you do more songs like this one", "Now playing: Dreams by Fleetwood Mac. Volume 100.", "" },
        { "mix", "Sam: Gunnabot make us a mix of Tame Impala", "Volume 100.", "Tame Impala" },
        { "skipto", "Sam: bot, skip to the Fleetwood Mac one", "Up next: Africa; Dreams; Mr. Brightside. Volume 100.", "Fleetwood Mac" },
        { "skipto", "Sam: bot go to number two", "Up next: Africa; Dreams; Mr. Brightside. Volume 100.", "2" },
        { "volume", "Sam: Ganabot, turn it up.", "Now playing: Dreams by Fleetwood Mac. Volume 20.", "+20" },
        { "volume", "Regan: bot can you turn it down to seventy-five", "Volume 120.", "75" },
        { "volume", "Sam: Gunnabot quieter please, way quieter", "Volume 90.", "-40" },
        { "volume", "Regan: robot, up by ten", "Volume 60.", "+10" },
    };

    /** Asks the chat model what a command needs: the song, the station or the new volume. */
    String args(String command, String line, String player) throws IOException, InterruptedException
    {
        JSONArray messages = new JSONArray().put(message("system", ARGS_SYSTEM));
        for (String[] ex : ARGS_EXAMPLES)
        {
            messages.put(message("user", "Command: " + ex[0] + "\nLine: " + ex[1] + "\nPlayer: " + ex[2]));
            messages.put(message("assistant", new JSONObject().put("args", ex[3]).toString()));
        }
        messages.put(message("user", "Command: " + command + "\nLine: " + line + "\nPlayer: " + player));
        String c = complete(messages, 48).replaceAll("(?s)<think>.*?</think>", "");
        int start = c.indexOf('{'), end = c.lastIndexOf('}');
        if (start < 0 || end <= start)
            throw new JSONException("no JSON in: " + c);
        return new JSONObject(c.substring(start, end + 1)).optString("args", "").trim();
    }

    private static final Pattern VOLUME_NOW = Pattern.compile("Volume (\\d+)\\.");
    private static final Pattern VOLUME_ASKED = Pattern.compile("([+-])?\\s*(\\d{1,3})\\s*%?");

    /**
     * The args if the command can run with them, or null if it can't. A volume
     * comes back as the number to set: small models are poor at sums, so the
     * chat model says "+20" and the adding is done here.
     * @param player the player state, which ends with the volume now
     */
    static String checkArgs(String command, String args, String player)
    {
        switch (command)
        {
            case "volume":
                Matcher asked = VOLUME_ASKED.matcher(args);
                if (!asked.matches())
                    return null;
                int n = Integer.parseInt(asked.group(2));
                if (asked.group(1) == null)
                    return n <= 150 ? String.valueOf(n) : null;
                // the line ends with the volume; a song called "Hits Volume 2." comes before it
                Matcher now = VOLUME_NOW.matcher(player);
                String current = null;
                while (now.find())
                    current = now.group(1);
                if (current == null)
                    return null;
                int volume = Integer.parseInt(current) + (asked.group(1).equals("-") ? -n : n);
                return String.valueOf(Math.max(0, Math.min(150, volume)));
            case "mix":
                return args; // empty means like what's playing
            default:
                return args.isEmpty() ? null : args;
        }
    }

    /**
     * On OpenRouter, sends a second copy if the first hasn't answered in
     * hedgeAfterMs and takes whichever answers first: now and then a host
     * stalls for 7-9 s on a request it normally answers in one. A local
     * server gets one request, since a copy would only queue behind it.
     */
    private HttpResponse<String> send(HttpRequest request, long hedgeAfterMs) throws IOException, InterruptedException
    {
        CompletableFuture<HttpResponse<String>> first = sendRetrying(request);
        if (!openRouter)
            return await(first);
        try
        {
            return first.get(hedgeAfterMs, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            // still waiting: hedge below
        }
        catch (ExecutionException e)
        {
            throw asIOException(e);
        }
        CompletableFuture<HttpResponse<String>> second = sendRetrying(request);
        CompletableFuture<HttpResponse<String>> winner = new CompletableFuture<>();
        AtomicInteger failures = new AtomicInteger();
        for (CompletableFuture<HttpResponse<String>> f : List.of(first, second))
            f.whenComplete((r, e) ->
            {
                if (e == null)
                    winner.complete(r);
                else if (failures.incrementAndGet() == 2)
                    winner.completeExceptionally(e);
            });
        try
        {
            return await(winner);
        }
        finally
        {
            first.cancel(true);
            second.cancel(true);
        }
    }

    private CompletableFuture<HttpResponse<String>> sendRetrying(HttpRequest request)
    {
        // the server may have dropped an idle keep-alive connection; retry once
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .exceptionallyCompose(e -> http.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
    }

    private static HttpResponse<String> await(CompletableFuture<HttpResponse<String>> f) throws IOException, InterruptedException
    {
        try
        {
            return f.get();
        }
        catch (ExecutionException e)
        {
            throw asIOException(e);
        }
    }

    private static IOException asIOException(ExecutionException e)
    {
        Throwable c = e.getCause() instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null
                ? ce.getCause() : e.getCause();
        return c instanceof IOException io ? io : new IOException(c);
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

    /**
     * What the bot says as a command runs, or null for the commands that say
     * their own piece. A song from the play history is asked for by its URL,
     * which is no good read out.
     */
    static String ack(String command, String args)
    {
        String ack = ACKS.get(command);
        if (ack == null)
            return null;
        return args.matches("(?i)https?://\\S+") ? "Queuing that up again." : String.format(ack, args);
    }
}
