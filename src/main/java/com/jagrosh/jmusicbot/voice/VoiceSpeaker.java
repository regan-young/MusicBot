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

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Plays text-to-speech clips one after another on a guild's voice player.
 * AudioHandler sends this player's frames in place of the music while it's
 * speaking, so music pauses for the clip and then carries on. Each clip is a
 * temp WAV file, deleted once it has played.
 */
public class VoiceSpeaker extends AudioEventAdapter
{
    private final AudioPlayer player;
    private final Queue<AudioTrack> pending = new ArrayDeque<>();

    public VoiceSpeaker(AudioPlayer player)
    {
        this.player = player;
        player.addListener(this);
    }

    public AudioPlayer getPlayer()
    {
        return player;
    }

    /** Whether it's quiet: nothing playing and nothing queued. */
    public synchronized boolean isIdle()
    {
        return player.getPlayingTrack() == null && pending.isEmpty();
    }

    public synchronized void enqueue(AudioTrack track)
    {
        if (!player.startTrack(track, true))
            pending.add(track);
    }

    public synchronized void clear()
    {
        pending.forEach(VoiceSpeaker::deleteFile);
        pending.clear();
        player.stopTrack();
    }

    @Override
    public synchronized void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason)
    {
        deleteFile(track);
        AudioTrack next = pending.poll();
        if (next != null)
            player.startTrack(next, false);
    }

    private static void deleteFile(AudioTrack track)
    {
        Path file = track.getUserData(Path.class);
        if (file == null)
            return;
        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException ignored) {}
    }
}
