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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Searches radio-browser.info, a free community directory of ~50,000 internet
 * radio stations, so ?radio can play stations beyond the curated list.
 */
public class RadioBrowser {
    private static final Logger LOG = LoggerFactory.getLogger(RadioBrowser.class);
    // "all" is round-robin DNS over the mirrors (and sometimes 502s); the
    // named ones are fallbacks
    private static final String[] HOSTS = {
            "all.api.radio-browser.info", "de1.api.radio-browser.info", "de2.api.radio-browser.info" };
    private static final String USER_AGENT = "GunnaBot/1.0 (JMusicBot fork)";

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build();

    public static class Station {
        public final String uuid, name, url, countryCode, country, favicon, codec, homepage;
        public final int bitrate;
        public final List<String> tags;
        final int clicks;

        Station(JSONObject o) {
            this.uuid = o.optString("stationuuid");
            this.name = o.optString("name").trim();
            this.url = o.optString("url_resolved", o.optString("url"));
            this.countryCode = o.optString("countrycode").toUpperCase(Locale.ROOT);
            this.country = o.optString("country");
            String fav = o.optString("favicon");
            // Discord can't render SVG thumbnails
            this.favicon = fav.startsWith("http") && !fav.toLowerCase(Locale.ROOT).endsWith(".svg") ? fav : null;
            this.codec = o.optString("codec");
            String home = o.optString("homepage");
            this.homepage = home.startsWith("http") ? home : null;
            this.bitrate = o.optInt("bitrate");
            this.tags = Arrays.stream(o.optString("tags").split(","))
                    .map(String::trim).filter(t -> !t.isEmpty()).collect(Collectors.toList());
            this.clicks = o.optInt("clickcount");
        }

        /** "Centreforce 883 (GB)" */
        public String label() {
            return countryCode.isEmpty() ? name : name + " (" + countryCode + ")";
        }

        /** "AAC · 160 kbps", or null if the directory doesn't say. */
        public String format() {
            String c = codec.isEmpty() || codec.equalsIgnoreCase("UNKNOWN") ? null : codec;
            String b = bitrate > 0 ? bitrate + " kbps" : null;
            if (c == null && b == null)
                return null;
            return c == null ? b : b == null ? c : c + " · " + b;
        }

        /** "🇺🇸 The United States Of America", or null. */
        public String origin() {
            String flag = flag(countryCode);
            String where = country.isEmpty() ? countryCode : country;
            if (where.isEmpty())
                return null;
            return flag == null ? where : flag + " " + where;
        }

        /** The first few genre tags, e.g. "indie, alternative, rock", or null. */
        public String genre() {
            return tags.isEmpty() ? null : String.join(", ", tags.subList(0, Math.min(3, tags.size())));
        }

        public RadioMetadata.StationInfo toInfo() {
            return new RadioMetadata.StationInfo(label(), null, favicon, format(), origin(), genre(), homepage);
        }
    }

    /** Country flag emoji for a two-letter code ("NZ" -> 🇳🇿), or null. */
    public static String flag(String countryCode) {
        if (countryCode == null || countryCode.length() != 2 || !countryCode.chars().allMatch(Character::isLetter))
            return null;
        String cc = countryCode.toUpperCase(Locale.ROOT);
        return new String(Character.toChars(0x1F1E6 + cc.charAt(0) - 'A'))
                + new String(Character.toChars(0x1F1E6 + cc.charAt(1) - 'A'));
    }

    /**
     * Stations matching a name (or, failing that, a genre like "jazz"), most
     * popular first. Also tries the name without spaces ("centre force" finds
     * "Centreforce 883").
     */
    public List<Station> search(String query, int limit) {
        List<Station> found = new ArrayList<>(query("name", query, limit));
        String squashed = query.replaceAll("\\s+", "");
        if (!squashed.equals(query))
            found.addAll(query("name", squashed, limit));
        if (found.size() < limit)
            found.addAll(query("tag", query.toLowerCase(Locale.ROOT), limit));
        found.sort((a, b) -> Integer.compare(b.clicks, a.clicks));
        return dedupe(found, limit);
    }

    /**
     * The most-voted stations in the directory. (By votes rather than recent
     * clicks, which a handful of stations dominate.)
     */
    public List<Station> popular(int limit) {
        JSONArray results = get("/json/stations/topvote/" + (limit * 3), b -> b.addQueryParameter("hidebroken", "true"));
        return dedupe(playable(results), limit);
    }

    /** A random station from the most-voted few hundred. */
    public Station random() {
        List<Station> popular = popular(300);
        return popular.isEmpty() ? null : popular.get(ThreadLocalRandom.current().nextInt(popular.size()));
    }

    /** The station with this radio-browser id, or null. */
    public Station byUuid(String uuid) {
        List<Station> s = playable(get("/json/stations/byuuid/" + uuid, b -> { }));
        return s.isEmpty() ? null : s.get(0);
    }

    /** The directory's entry for a stream URL, or null (used to find curated stations' logos). */
    public Station byUrl(String url) {
        List<Station> s = playable(get("/json/stations/byurl", b -> b.addQueryParameter("url", url)));
        return s.isEmpty() ? null : s.get(0);
    }

    private List<Station> query(String field, String value, int limit) {
        return playable(get("/json/stations/search", b -> b
                .addQueryParameter(field, value)
                .addQueryParameter("hidebroken", "true")
                .addQueryParameter("order", "clickcount")
                .addQueryParameter("reverse", "true")
                .addQueryParameter("limit", String.valueOf(limit * 3))));
    }

    /** Leaves out broken stations and HLS streams, which lavaplayer's HTTP source can't play. */
    private static List<Station> playable(JSONArray results) {
        List<Station> stations = new ArrayList<>();
        if (results == null)
            return stations;
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            if (o.optInt("hls") == 1 || o.optInt("lastcheckok", 1) == 0)
                continue;
            Station s = new Station(o);
            // Very long names are keyword spam ("Radio Charts - DJ Charts - Top 100 - ...")
            if (!s.url.isEmpty() && !s.name.isEmpty() && s.name.length() <= 80)
                stations.add(s);
        }
        return stations;
    }

    /**
     * The directory lists many stations more than once (one entry per stream
     * quality). Keep one per name and country, at its most popular position,
     * but with the highest-bitrate stream.
     */
    private static List<Station> dedupe(List<Station> stations, int limit) {
        Map<String, Station> best = new LinkedHashMap<>();
        for (Station s : stations) {
            String key = s.name.toLowerCase(Locale.ROOT) + "|" + s.countryCode;
            Station kept = best.get(key);
            if (kept == null)
                best.put(key, s);
            else if (s.bitrate > kept.bitrate)
                best.replace(key, s);
        }
        return best.values().stream().limit(limit).collect(Collectors.toList());
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
