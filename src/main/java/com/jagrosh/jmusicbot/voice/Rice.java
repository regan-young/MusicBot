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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads this week's supermarket specials from rice (Regan's specials tracker:
 * Pak'nSave, New World and Woolworths, scraped weekly) and turns them into
 * compact text for the chat LLM to answer from: the top deals at each store,
 * New World's mailer front page, and a search when they ask about something.
 */
public class Rice
{
    private static final Logger LOG = LoggerFactory.getLogger(Rice.class);
    static final Map<String, String> STORES = Map.of("paknsave", "Pak'nSave", "newworld", "New World", "woolworths", "Woolworths");

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    /** @param baseUrl rice's root, e.g. http://192.168.0.100:4000, or empty when it isn't set up */
    public Rice(String baseUrl)
    {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }

    public boolean isEnabled()
    {
        return !baseUrl.isEmpty();
    }

    /**
     * Everything the LLM needs for a question about specials, as plain text.
     * @param search what they asked about ("chicken thighs"), or empty for the week's best deals
     */
    public String briefing(String search) throws IOException, InterruptedException
    {
        StringBuilder sb = new StringBuilder();
        sb.append("Top grocery deals at each store this week (best first):\n");
        sb.append(lines(new JSONArray(get("/api/specials/top?per_store=6"))));
        if (search != null && !search.isBlank())
        {
            String q = URLEncoder.encode(search.trim(), StandardCharsets.UTF_8);
            JSONArray found = new JSONArray(get("/api/specials?limit=15&search=" + q));
            sb.append("\nSpecials matching \"").append(search.trim()).append("\":\n");
            sb.append(found.isEmpty() ? "(none on special this week)\n" : lines(found));
        }
        try
        {
            sb.append("\n").append(frontPage(new JSONObject(get("/api/mailer/front-page?region=NI"))));
        }
        catch (IOException e)
        {
            // the mailer comes from a third party; the rest still answers most questions
            LOG.warn("Could not get New World's mailer front page", e);
        }
        return sb.toString();
    }

    /** One line per special: store, name, price, and the saving when it's known. */
    static String lines(JSONArray specials)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < specials.length(); i++)
            sb.append("- ").append(line(specials.getJSONObject(i))).append('\n');
        return sb.toString();
    }

    static String line(JSONObject s)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(STORES.getOrDefault(s.optString("store"), s.optString("store"))).append(": ").append(s.optString("name"));
        double sale = number(s.opt("sale_price")), was = number(s.opt("original_price"));
        if (!Double.isNaN(sale))
            sb.append(String.format(Locale.ROOT, " $%.2f", sale)).append("kg".equals(s.optString("unit")) ? "/kg" : "");
        if (!Double.isNaN(was) && was > sale)
            sb.append(String.format(Locale.ROOT, " (was $%.2f, %d%% off)", was, Math.round((1 - sale / was) * 100)));
        String promo = s.optString("promo_label", "");
        if (!promo.isEmpty() && !"null".equals(promo))
            sb.append(" [").append(promo).append("]");
        return sb.toString();
    }

    static String frontPage(JSONObject fp)
    {
        StringBuilder sb = new StringBuilder("New World's mailer front page (their headline deals, ")
                .append(fp.getJSONObject("catalogue").optString("title")).append("):\n");
        JSONArray items = fp.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++)
        {
            JSONObject item = items.getJSONObject(i);
            JSONObject match = item.optJSONObject("match");
            sb.append("- ").append(item.optString("name"));
            // the match is the closest product in the scrape; only trust a close one
            if (match != null && match.optDouble("score", 0) >= 0.6 && !Double.isNaN(number(match.opt("sale_price"))))
                sb.append(String.format(Locale.ROOT, " about $%.2f", number(match.opt("sale_price"))));
            sb.append('\n');
        }
        sb.append("Mailer: ").append(fp.getJSONObject("catalogue").optString("viewerUrl")).append('\n');
        return sb.toString();
    }

    /** Prices come back from Postgres as strings ("12.79"). */
    private static double number(Object v)
    {
        if (v instanceof Number n)
            return n.doubleValue();
        try
        {
            return v == null || JSONObject.NULL.equals(v) ? Double.NaN : Double.parseDouble(v.toString());
        }
        catch (NumberFormatException e)
        {
            return Double.NaN;
        }
    }

    private String get(String path) throws IOException, InterruptedException
    {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new IOException("rice " + path + ": HTTP " + res.statusCode());
        return res.body();
    }
}
