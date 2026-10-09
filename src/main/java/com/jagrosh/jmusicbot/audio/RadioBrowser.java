/*
 * Copyright 2026
 *
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

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Searches radio-browser.info, a free community directory of ~50,000 internet
 * radio stations, so ?radio can play stations beyond the curated list.
 */
public class RadioBrowser {
    private static final Logger LOG = LoggerFactory.getLogger(RadioBrowser.class);
    // "all" is round-robin DNS over the mirrors; the named ones are fallbacks
    private static final String[] HOSTS = {
            "all.api.radio-browser.info", "de1.api.radio-browser.info", "de2.api.radio-browser.info" };
    private static final String USER_AGENT = "GunnaBot/1.0 (JMusicBot fork)";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build();

    public static class Station {
        public final String uuid, name, url, countryCode, favicon;
        final int clicks;

        Station(JSONObject o) {
            this.uuid = o.optString("stationuuid");
            this.name = o.optString("name").trim();
            this.url = o.optString("url_resolved", o.optString("url"));
            this.countryCode = o.optString("countrycode");
            String fav = o.optString("favicon");
            this.favicon = fav.startsWith("http") ? fav : null;
            this.clicks = o.optInt("clickcount");
        }

        /** "Centre Force Radio (GB)" */
        public String label() {
            return countryCode.isEmpty() ? name : name + " (" + countryCode + ")";
        }
    }

    /**
     * Stations whose name matches, most popular first. Leaves out stations
     * the directory has found broken and HLS streams, which lavaplayer's HTTP
     * source cannot play.
     */
    public List<Station> search(String name, int limit) {
        List<Station> stations = searchExact(name, limit);
        // "centre force" should also find "Centreforce 883"
        String squashed = name.replaceAll("\\s+", "");
        if (!squashed.equals(name)) {
            stations.addAll(searchExact(squashed, limit));
            stations.sort((a, b) -> Integer.compare(b.clicks, a.clicks));
        }
        // The directory lists many stations more than once
        Set<String> seen = new HashSet<>();
        stations.removeIf(st -> !seen.add(st.name.toLowerCase() + "|" + st.countryCode));
        return stations.size() > limit ? new ArrayList<>(stations.subList(0, limit)) : stations;
    }

    private List<Station> searchExact(String name, int limit) {
        JSONArray results = get("/json/stations/search", b -> b
                .addQueryParameter("name", name)
                .addQueryParameter("hidebroken", "true")
                .addQueryParameter("order", "clickcount")
                .addQueryParameter("reverse", "true")
                .addQueryParameter("limit", String.valueOf(limit * 3)));
        List<Station> stations = new ArrayList<>();
        if (results == null)
            return stations;
        for (int i = 0; i < results.length() && stations.size() < limit; i++) {
            JSONObject o = results.getJSONObject(i);
            if (o.optInt("hls") == 1 || o.optInt("lastcheckok", 1) == 0)
                continue;
            Station s = new Station(o);
            if (!s.url.isEmpty() && !s.name.isEmpty())
                stations.add(s);
        }
        return stations;
    }

    /** The station with this radio-browser id, or null. */
    public Station byUuid(String uuid) {
        JSONArray results = get("/json/stations/byuuid/" + uuid, b -> { });
        return results == null || results.isEmpty() ? null : new Station(results.getJSONObject(0));
    }

    private interface Params {
        void add(HttpUrl.Builder builder);
    }

    private JSONArray get(String path, Params params) {
        for (String host : HOSTS) {
            HttpUrl.Builder url = new HttpUrl.Builder().scheme("https").host(host).encodedPath(path);
            params.add(url);
            Request req = new Request.Builder().url(url.build()).header("User-Agent", USER_AGENT).build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null)
                    return new JSONArray(resp.body().string());
            } catch (Exception e) {
                LOG.debug("radio-browser request to {} failed", host, e);
            }
        }
        LOG.warn("radio-browser.info is unreachable");
        return null;
    }
}
