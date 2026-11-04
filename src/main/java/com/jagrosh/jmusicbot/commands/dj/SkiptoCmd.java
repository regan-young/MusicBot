/*
 * Copyright 2016 John Grosh <john.a.grosh@gmail.com>.
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
package com.jagrosh.jmusicbot.commands.dj;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 *
 * @author John Grosh <john.a.grosh@gmail.com>
 */
public class SkiptoCmd extends DJCommand
{
    private static final List<String> NUMBER_WORDS = List.of("one", "two", "three", "four", "five", "six", "seven",
            "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen", "twenty");
    // how people talk about a song rather than its name: "the Fleetwood Mac one"
    private static final Set<String> FILLER = Set.of("the", "a", "one", "song", "by");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES = Pattern.compile("['’]");
    private static final Pattern NOT_WORDS = Pattern.compile("[^\\p{L}\\p{N}]+");

    public SkiptoCmd(Bot bot)
    {
        super(bot);
        this.name = "skipto";
        this.help = "skips to a song in the queue, keeping the rest queued";
        this.arguments = "<position or name>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.bePlaying = true;
    }

    @Override
    public void doCommand(CommandEvent event)
    {
        AudioHandler handler = (AudioHandler)event.getGuild().getAudioManager().getSendingHandler();
        if(handler.getQueue().isEmpty())
        {
            event.reply(event.getClient().getError()+" There's nothing in the queue to skip to!");
            return;
        }
        if(event.getArgs().isBlank())
        {
            event.reply(event.getClient().getError()+" Which song? `"+event.getClient().getPrefix()+this.name+" "+this.arguments+"`");
            return;
        }
        int position = findPosition(songs(handler.getQueue().getList()), event.getArgs());
        if(position<0)
        {
            event.reply(event.getClient().getError()+" Nothing in the queue matches `"+FormatUtil.filter(event.getArgs())+"`.");
            return;
        }
        // moved rather than skipped past, so people can jump around without losing what's queued
        QueuedTrack next = handler.getQueue().moveItem(position, 0);
        // onTrackEnd only puts a finished track back, so each jump would shrink the loop
        AudioTrack current = handler.getPlayer().getPlayingTrack();
        if(bot.getSettingsManager().getSettings(event.getGuild()).getRepeatMode()==RepeatMode.ALL && !bot.getRadioMetadata().isRadio(current))
            handler.getQueue().add(new QueuedTrack(current.makeClone(), current.getUserData(RequestMetadata.class)));
        event.reply(event.getClient().getSuccess()+" Skipped to **"+next.getTrack().getInfo().title+"**");
        handler.getPlayer().stopTrack();
    }

    /** What to match each queued track against: its title and uploader. */
    public static List<String> songs(List<QueuedTrack> queue)
    {
        return queue.stream().map(q ->
        {
            AudioTrackInfo info = q.getTrack().getInfo();
            return info.title + " " + (info.author == null ? "" : info.author);
        }).collect(Collectors.toList());
    }

    /**
     * The index of the song someone asked for. "3" and "number three" are
     * positions; anything else is tried as a name first, because "1979" (when
     * the queue is shorter than that) and "Song 2" can be titles.
     * @return the index, or -1 if there's no such song
     */
    public static int findPosition(List<String> songs, String wanted)
    {
        String asked = wanted.trim().toLowerCase(Locale.ROOT);
        int position = numbered(asked, "(?:number|#)", songs.size());
        if(position>=0)
            return position;
        if(asked.matches("\\d+"))
        {
            position = numbered(asked, "", songs.size());
            if(position>=0)
                return position;
        }
        // the exact phrase first, so "good luck" prefers "Good Luck, Babe!" to "Luck Be Good"
        String phrase = clean(wanted);
        for(int i=0; i<songs.size(); i++)
            if(!phrase.isEmpty() && (" "+clean(songs.get(i))+" ").contains(" "+phrase+" "))
                return i;
        for(int i=0; i<songs.size(); i++)
            if(matches(songs.get(i), wanted))
                return i;
        return numbered(asked, "(?:song|track)?", songs.size());
    }

    /** The index for a number in digits or words after this prefix, or -1. */
    private static int numbered(String asked, String prefix, int size)
    {
        Matcher m = Pattern.compile("^"+prefix+"\\s*(\\d{1,6}|\\p{Alpha}+)$").matcher(asked);
        if(!m.matches())
            return -1;
        String number = m.group(1);
        int p = number.matches("\\d+") ? Integer.parseInt(number) : NUMBER_WORDS.indexOf(number)+1;
        return p>=1 && p<=size ? p-1 : -1;
    }

    /**
     * Whether a song has every word that was asked for, in any order. A word
     * can be the start of one ("dream" for Dreams, for someone still typing),
     * but not the middle: "rain" isn't Train, and "5" isn't 2005.
     */
    public static boolean matches(String song, String wanted)
    {
        String phrase = clean(wanted);
        if(phrase.isEmpty())
            return false;
        List<String> name = Arrays.asList(clean(song).split(" "));
        List<String> words = Arrays.stream(phrase.split(" ")).filter(w -> !FILLER.contains(w)).collect(Collectors.toList());
        return (words.isEmpty() ? Arrays.asList(phrase.split(" ")) : words).stream().allMatch(w ->
                name.stream().anyMatch(n -> n.equals(w) || (!w.matches("\\d+") && n.startsWith(w))));
    }

    // speech-to-text says "beyonce" and "dont" for Beyoncé and Don't
    private static String clean(String s)
    {
        String plain = MARKS.matcher(Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
        return NOT_WORDS.matcher(APOSTROPHES.matcher(plain).replaceAll("")).replaceAll(" ").trim();
    }
}
