package com.jagrosh.jmusicbot.voice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs the voice router over a file of labelled lines and reports how often
 * the bot would have done the right thing, and how long the router took. Not a
 * test: it calls the real models, so it needs a key and costs a little.
 *
 * <pre>
 * OPENROUTER_API_KEY=... java -cp target/classes:target/test-classes:$(cat target/cp.txt) \
 *     com.jagrosh.jmusicbot.voice.RouterBench cases.jsonl google/gemma-3-12b-it [google/gemini-decision-1]
 * </pre>
 *
 * Each line of the file is one case: {"id", "conversation": ["Name: text", ...],
 * "named", "followUp", "player", "crude": true/false/null, "gold": ["chat", ...],
 * "args": "word the args must contain" or "low-high" for a volume}. gold lists
 * every acceptable outcome: "none" (not for the bot), "chat", or a command.
 * The cases are real voice chat, so they stay out of the repository.
 */
public class RouterBench
{
    private record Result(JSONObject c, String got, String args, double confidence, long ms, String error) {}

    private static final Pattern VOLUME = Pattern.compile("Volume (\\d+)");

    public static void main(String[] a) throws Exception
    {
        if (a.length < 2)
        {
            System.err.println("usage: RouterBench <cases.jsonl> <chat model> [decision model]");
            System.exit(2);
        }
        VoiceRouter router = new VoiceRouter("https://openrouter.ai/api", System.getenv("OPENROUTER_API_KEY"), a[1],
                a.length > 2 ? a[2] : null);
        List<JSONObject> cases = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(a[0]), StandardCharsets.UTF_8))
            if (!line.isBlank())
                cases.add(new JSONObject(line));

        // a few at a time: enough to finish soon, not enough to slow each other down
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Result>> running = new ArrayList<>();
        for (JSONObject c : cases)
            running.add(pool.submit(() -> run(router, c)));
        pool.shutdown();

        int right = 0, buttedIn = 0, missed = 0, wrongCommand = 0, wrongArgs = 0, failed = 0;
        List<Long> times = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        for (Future<Result> f : running)
        {
            Result r = f.get();
            JSONArray conversation = r.c.getJSONArray("conversation");
            String last = conversation.getString(conversation.length() - 1);
            List<Object> gold = r.c.getJSONArray("gold").toList();
            String what = String.format("%-9s wanted %-9s got %-9s%s %s | %s", r.c.getString("id"),
                    String.join("/", gold.stream().map(Object::toString).toList()), r.got,
                    r.args.isEmpty() ? "" : " [" + r.args + "]",
                    Double.isNaN(r.confidence) ? "" : String.format("(%.2f)", r.confidence),
                    last.length() > 110 ? last.substring(0, 110) : last);
            if (r.error != null)
            {
                failed++;
                wrong.add("FAILED     " + what + " " + r.error);
                continue;
            }
            times.add(r.ms);
            if (!gold.contains(r.got))
            {
                if (gold.contains("none"))
                {
                    buttedIn++;
                    wrong.add("BUTTED IN  " + what);
                }
                else if (r.got.equals("none"))
                {
                    missed++;
                    wrong.add("MISSED     " + what);
                }
                else
                {
                    wrongCommand++;
                    wrong.add("WRONG CMD  " + what);
                }
            }
            else if (!argsFit(r.c.optString("args", ""), r.got, r.args))
            {
                wrongArgs++;
                wrong.add("WRONG ARGS " + what + " (wanted " + r.c.optString("args") + ")");
            }
            else
                right++;
        }
        wrong.forEach(System.out::println);
        Collections.sort(times);
        int n = cases.size();
        System.out.printf("%n%s%n%d of %d right (%.1f%%): butted in %d, missed %d, wrong command %d, wrong args %d, failed %d%n",
                router.describe(), right, n, 100.0 * right / n, buttedIn, missed, wrongCommand, wrongArgs, failed);
        if (!times.isEmpty())
            System.out.printf("router time: median %d ms, 90%% under %d ms, slowest %d ms%n", times.get(times.size() / 2),
                    times.get(times.size() * 9 / 10), times.get(times.size() - 1));
    }

    /** Routes one case and works out what the bot would do, the way VoiceManager does. */
    private static Result run(VoiceRouter router, JSONObject c)
    {
        List<String> conversation = c.getJSONArray("conversation").toList().stream().map(Object::toString).toList();
        String last = conversation.get(conversation.size() - 1);
        String said = last.substring(last.indexOf(": ") + 2);
        boolean named = c.getBoolean("named"), followUp = c.getBoolean("followUp");
        String player = c.getString("player");
        Boolean crude = c.isNull("crude") ? null : c.getBoolean("crude");
        Matcher m = VOLUME.matcher(player);
        int volume = m.find() ? Integer.parseInt(m.group(1)) : 100;
        if (!named && !followUp && SpokenText.isReaction(said))
            return new Result(c, "none", "", Double.NaN, 0, null);
        long t = System.currentTimeMillis();
        VoiceRouter.Route route;
        try
        {
            route = router.route(conversation, followUp, player, null, crude);
        }
        catch (Exception e)
        {
            return new Result(c, "none", "", Double.NaN, 0, e.toString());
        }
        long ms = System.currentTimeMillis() - t;
        boolean wanted = VoiceRouter.forBot(route, named, followUp);
        boolean repeat = wanted && route.toBot && VoiceRouter.isRepeat(route.command, route.args, said, null, crude, volume);
        boolean toBot = wanted && (!repeat || named || followUp);
        String command = !toBot ? "none" : !route.toBot || repeat ? "chat" : route.command;
        return new Result(c, command, command.equals("none") || command.equals("chat") ? "" : route.args,
                route.scored ? route.confidence : Double.NaN, ms, null);
    }

    /** Whether the args do what the case wants: a volume in "low-high", or text containing the word. */
    private static boolean argsFit(String wanted, String command, String args)
    {
        if (wanted.isEmpty() || command.equals("chat") || command.equals("none"))
            return true;
        if (command.equals("volume"))
        {
            String[] range = wanted.split("-");
            if (!args.matches("\\d+"))
                return false;
            int v = Integer.parseInt(args);
            return v >= Integer.parseInt(range[0]) && v <= Integer.parseInt(range[1]);
        }
        return args.toLowerCase(java.util.Locale.ROOT).contains(wanted.toLowerCase(java.util.Locale.ROOT));
    }
}
