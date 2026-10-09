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

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Album art and YouTube ids for tracks. */
public final class TrackArt {
    // A YouTube video id inside a watch/youtu.be/shorts/embed URL
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})");
    private static final Pattern BARE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private TrackArt() {
    }

    /**
     * Cover art for a track, or null. Radio stations use the current song's
     * art when the station publishes it; YouTube tracks (which the yt-dlp
     * source leaves without artwork) use the video thumbnail.
     */
    public static String artworkUrl(AudioTrack track, RadioMetadata radio) {
        if (track == null)
            return null;
        if (radio.isRadio(track))
            return radio.getSongArt(track);
        String art = track.getInfo().artworkUrl;
        if (art != null && !art.isBlank())
            return art;
        String uri = track.getInfo().uri;
        if (uri != null && (uri.contains("youtube.com") || uri.contains("youtu.be"))) {
            String id = youtubeId(track);
            if (id != null)
                return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        }
        return null;
    }

    /** The YouTube video id of a track, from its identifier or URL, or null. */
    public static String youtubeId(AudioTrack track) {
        String fromIdentifier = youtubeId(track.getInfo().identifier);
        return fromIdentifier != null ? fromIdentifier : youtubeId(track.getInfo().uri);
    }

    /** A YouTube video id from a bare id or a YouTube URL, or null. */
    public static String youtubeId(String s) {
        if (s == null || s.isEmpty())
            return null;
        if (BARE_ID.matcher(s).matches())
            return s;
        Matcher matcher = VIDEO_ID.matcher(s);
        return matcher.find() ? matcher.group(1) : null;
    }
}
