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

import com.jagrosh.jmusicbot.Bot;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks what song a live radio station is playing, so the bot status and
 * now-playing message can show it.
 *
 * Most stations embed the title in the stream itself (ICY "StreamTitle"
 * metadata); we read it over a short side connection rather than from the
 * playing stream. Radio France stations have no ICY metadata, so they are
 * read from Radio France's livemeta API instead.
 */
public class RadioMetadata {
    private static final Logger LOG = LoggerFactory.getLogger(RadioMetadata.class);
    private static final long POLL_SECONDS = 20;
    private static final Pattern STREAM_TITLE = Pattern.compile("StreamTitle='(.*?)';", Pattern.DOTALL);
    // Some stations send "Artist - Title - Artist - Title"
    private static final Pattern DOUBLED = Pattern.compile("^(.+) - \\1$");

    private final Bot bot;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    // Separate from the bot's single-threaded pool so slow stations can't stall it
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "radio-metadata");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, Station> stationsByUrl = new ConcurrentHashMap<>();
    private final Map<String, String> titleByUrl = new ConcurrentHashMap<>();
    private final Map<Long, ScheduledFuture<?>> pollByGuild = new ConcurrentHashMap<>();

    private static class Station {
        final String name;
        final String livemetaUrl; // null = read ICY metadata from the stream

        Station(String name, String livemetaUrl) {
            this.name = name;
            this.livemetaUrl = livemetaUrl;
        }
    }

    public RadioMetadata(Bot bot) {
        this.bot = bot;
    }

    public void register(String streamUrl, String name, String livemetaUrl) {
        stationsByUrl.put(streamUrl, new Station(name, livemetaUrl));
    }

    /** Station display name if this track is a registered radio station, else null. */
    public String getStationName(AudioTrack track) {
        Station s = track == null ? null : stationsByUrl.get(track.getInfo().uri);
        return s == null ? null : s.name;
    }

    /** True if this track is one of the ?radio stations. */
    public boolean isRadio(AudioTrack track) {
        return getStationName(track) != null;
    }

    /** Current song on this track's station, or null if unknown. */
    public String getSongTitle(AudioTrack track) {
        return track == null ? null : titleByUrl.get(track.getInfo().uri);
    }

    /** Status/now-playing label, e.g. "📻 ZM · Artist - Title", or null if not a radio station. */
    public String getLabel(AudioTrack track) {
        String name = getStationName(track);
        if (name == null) {
            return null;
        }
        String song = getSongTitle(track);
        return "\uD83D\uDCFB " + name + (song == null ? "" : " \u00B7 " + song);
    }

    /** Start polling the station now playing in this guild; replaces any previous poll. */
    public void start(long guildId, String streamUrl) {
        stop(guildId);
        if (!stationsByUrl.containsKey(streamUrl)) {
            return;
        }
        pollByGuild.put(guildId, poller.scheduleWithFixedDelay(
                () -> poll(guildId, streamUrl), 0, POLL_SECONDS, TimeUnit.SECONDS));
    }

    public void stop(long guildId) {
        ScheduledFuture<?> f = pollByGuild.remove(guildId);
        if (f != null) {
            f.cancel(false);
        }
    }

    private AudioTrack playingTrack(long guildId) {
        Guild guild = bot.getJDA().getGuildById(guildId);
        if (guild == null) {
            return null;
        }
        AudioHandler handler = (AudioHandler) guild.getAudioManager().getSendingHandler();
        return handler == null ? null : handler.getPlayer().getPlayingTrack();
    }

    private boolean isPlaying(long guildId, String streamUrl) {
        AudioTrack track = playingTrack(guildId);
        return track != null && streamUrl.equals(track.getInfo().uri);
    }

    private void poll(long guildId, String streamUrl) {
        try {
            if (!isPlaying(guildId, streamUrl)) {
                stop(guildId);
                return;
            }
            Station station = stationsByUrl.get(streamUrl);
            String song = station.livemetaUrl != null
                    ? fetchRadioFrance(station.livemetaUrl)
                    : fetchIcy(streamUrl);
            if (Objects.equals(song, titleByUrl.get(streamUrl))) {
                return;
            }
            if (song == null) {
                titleByUrl.remove(streamUrl);
            } else {
                titleByUrl.put(streamUrl, song);
            }
            LOG.info("{} now playing: {}", station.name, song == null ? "(no title)" : song);
            // The station may have been stopped while we were fetching
            AudioTrack track = playingTrack(guildId);
            if (track != null && streamUrl.equals(track.getInfo().uri)) {
                bot.getNowplayingHandler().onTrackUpdate(track);
            }
        } catch (Exception e) {
            LOG.debug("Radio metadata poll failed for {}", streamUrl, e);
        }
    }

    private String fetchIcy(String streamUrl) throws Exception {
        Request req = new Request.Builder().url(streamUrl)
                .header("Icy-MetaData", "1")
                .header("User-Agent", "Mozilla/5.0")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String metaint = resp.header("icy-metaint");
            if (!resp.isSuccessful() || metaint == null) {
                return null;
            }
            InputStream in = resp.body().byteStream();
            in.skipNBytes(Integer.parseInt(metaint.trim()));
            int len = in.read() * 16;
            if (len <= 0) {
                return null;
            }
            String meta = new String(in.readNBytes(len), StandardCharsets.UTF_8);
            Matcher m = STREAM_TITLE.matcher(meta);
            return m.find() ? clean(m.group(1)) : null;
        }
    }

    private String fetchRadioFrance(String livemetaUrl) throws Exception {
        Request req = new Request.Builder().url(livemetaUrl).header("User-Agent", "Mozilla/5.0").build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                return null;
            }
            JSONObject steps = new JSONObject(resp.body().string()).optJSONObject("steps");
            if (steps == null) {
                return null;
            }
            long now = System.currentTimeMillis() / 1000;
            for (String key : steps.keySet()) {
                JSONObject step = steps.getJSONObject(key);
                if (!"song".equals(step.optString("embedType"))
                        || step.optLong("start") > now || step.optLong("end") <= now) {
                    continue;
                }
                List<String> artists = new ArrayList<>();
                JSONArray highlighted = step.optJSONArray("highlightedArtists");
                if (highlighted != null) {
                    for (int i = 0; i < highlighted.length(); i++) {
                        artists.add(highlighted.optString(i));
                    }
                }
                String artist = artists.isEmpty() ? step.optString("authors", "") : String.join(", ", artists);
                return clean(artist.isBlank() ? step.optString("title") : artist + " - " + step.optString("title"));
            }
            return null;
        }
    }

    private static String clean(String title) {
        if (title == null) {
            return null;
        }
        title = title.trim();
        Matcher m = DOUBLED.matcher(title);
        if (m.matches()) {
            title = m.group(1).trim();
        }
        return title.isEmpty() || title.equals("-") ? null : title;
    }
}
