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
package com.jagrosh.jmusicbot.audio;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The few Last.fm calls a mix needs: similar tracks, similar artists and an
 * artist's top tracks, all from millions of people's listening. Read-only,
 * with just an API key (config {@code lastfmkey}); no login. Every call
 * returns an empty list on any failure, so a mix carries on with YouTube
 * alone.
 */
public class LastFm
{
    private static final Logger LOG = LoggerFactory.getLogger(LastFm.class);
    private static final String API = "https://ws.audioscrobbler.com/2.0/";

    public record Song(String artist, String title)
    {
        public String query()
        {
            return artist + " - " + title;
        }
    }

    private final String key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();

    public LastFm(String key)
    {
        this.key = key == null ? "" : key.trim();
    }

    public boolean isEnabled()
    {
        return !key.isEmpty();
    }

    /** Tracks people listen to alongside this one, most similar first. */
    public List<Song> similarTracks(String artist, String title, int limit)
    {
        return songs(call("track.getsimilar", Map.of("artist", artist, "track", title, "limit", Integer.toString(limit))),
                "similartracks");
    }

    /** Artists like this one, most similar first. */
    public List<String> similarArtists(String artist, int limit)
    {
        JSONObject o = call("artist.getsimilar", Map.of("artist", artist, "limit", Integer.toString(limit)));
        List<String> out = new ArrayList<>();
        JSONArray arr = o == null || o.optJSONObject("similarartists") == null ? null
                : o.getJSONObject("similarartists").optJSONArray("artist");
        if (arr != null)
            for (int i = 0; i < arr.length(); i++)
            {
                String name = arr.getJSONObject(i).optString("name", "");
                if (!name.isBlank())
                    out.add(name);
            }
        return out;
    }

    /** An artist's most played tracks, most played first. */
    public List<Song> topTracks(String artist, int limit)
    {
        return songs(call("artist.gettoptracks", Map.of("artist", artist, "limit", Integer.toString(limit))), "toptracks");
    }

    /** The songs in a similartracks / toptracks answer. */
    static List<Song> songs(JSONObject o, String field)
    {
        List<Song> out = new ArrayList<>();
        JSONObject wrap = o == null ? null : o.optJSONObject(field);
        JSONArray arr = wrap == null ? null : wrap.optJSONArray("track");
        if (arr == null)
            return out;
        for (int i = 0; i < arr.length(); i++)
        {
            JSONObject t = arr.getJSONObject(i);
            Object a = t.opt("artist");
            String artist = a instanceof JSONObject ja ? ja.optString("name", "") : a == null ? "" : a.toString();
            String name = t.optString("name", "");
            if (!artist.isBlank() && !name.isBlank())
                out.add(new Song(artist, name));
        }
        return out;
    }

    private JSONObject call(String method, Map<String, String> params)
    {
        if (!isEnabled())
            return null;
        Map<String, String> all = new LinkedHashMap<>();
        all.put("method", method);
        all.putAll(params);
        all.put("autocorrect", "1");
        all.put("api_key", key);
        all.put("format", "json");
        StringBuilder url = new StringBuilder(API).append('?');
        all.forEach((k, v) -> url.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        try
        {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.substring(0, url.length() - 1)))
                    .timeout(Duration.ofSeconds(6)).header("User-Agent", "Gunnabot/1.0").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JSONObject o = new JSONObject(res.body());
            if (o.has("error"))
            {
                // 6 = not found, which is normal for odd YouTube titles
                if (o.optInt("error") != 6)
                    LOG.warn("Last.fm {} failed: {} {}", method, o.optInt("error"), o.optString("message"));
                return null;
            }
            return o;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (Exception e)
        {
            LOG.warn("Last.fm {} failed: {}", method, e.toString());
            return null;
        }
    }
}
