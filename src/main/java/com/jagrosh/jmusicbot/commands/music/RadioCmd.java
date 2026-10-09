/*
 * Copyright 2024
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
package com.jagrosh.jmusicbot.commands.music;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.PlayerControls;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.audio.RadioBrowser;
import com.jagrosh.jmusicbot.audio.RadioMetadata;
import com.jagrosh.jmusicbot.audio.RequestMetadata;
import com.jagrosh.jmusicbot.commands.MusicCommand;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ?radio: curated NZ stations and FIP's channels, plus any of ~50,000
 * stations worldwide via radio-browser.info. With no station it shows a
 * picker (dropdowns, worldwide search, popular, surprise me); playing a
 * station replies with a live card that follows the song.
 */
public class RadioCmd extends MusicCommand {
    // Component ids (the buttons on the radio card live in PlayerControls)
    public static final String PICK = PlayerControls.RADIO_PREFIX + "pick";
    public static final String WORLD_PICK = PlayerControls.RADIO_PREFIX + "world";
    public static final String POPULAR = PlayerControls.RADIO_PREFIX + "popular";
    public static final String RANDOM = PlayerControls.RADIO_PREFIX + "random";
    public static final String SEARCH_MODAL = PlayerControls.RADIO_PREFIX + "searchmodal";
    public static final String SEARCH_INPUT = "query";
    // Prefix for values naming a radio-browser station
    public static final String WORLD_PREFIX = "rb:";

    private static final String NZ = "🇳🇿 New Zealand", FIP_ORIGIN = "🇫🇷 Paris, France";
    private static final String FIP_HOME = "https://www.radiofrance.fr/fip";
    private static final String FIP_LOGO = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/16/FIP_logo_2021.svg/250px-FIP_logo_2021.svg.png";

    /** A curated station. */
    private record Curated(String key, String group, String url, RadioMetadata.StationInfo info) {
    }

    private final List<Curated> curated = new ArrayList<>();
    private final Map<String, Curated> byKey = new LinkedHashMap<>();
    private final Map<Long, String> currentStationByGuild = new ConcurrentHashMap<>();
    private final RadioBrowser browser = new RadioBrowser();

    public RadioCmd(Bot bot) {
        super(bot);
        this.name = "radio";
        this.help = "plays a radio station: NZ favourites, FIP, or any station worldwide";
        this.arguments = "<station|list|random|skip>";
        this.aliases = bot.getConfig().getAliases(this.name);
        this.beListening = true;
        this.bePlaying = false;

        // New Zealand. Each uses the best stream its broadcaster publishes:
        // NZME only offers 64 kbps HE-AAC (ZM 128); MediaWorks 130 kbps AAC.
        nz("rnz", "RNZ National", "News, talk & music", "AAC · 128 kbps",
                "http://radionz-ice.streamguys.com/National_aac128");
        nz("newstalkzb", "Newstalk ZB", "News & talk", "HE-AAC · 64 kbps",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_41AAC.aac");
        nz("zm", "ZM", "Hits", "HE-AAC · 128 kbps",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_03AAC.aac");
        nz("flava", "Flava", "Hip-hop & R&B", "HE-AAC · 64 kbps",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_08AAC_SC");
        nz("coast", "Coast", "Easy listening", "HE-AAC · 64 kbps",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_07AAC_SC");
        nz("gold", "Gold", "Classic hits", "MP3 · 96 kbps",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_05_SC");
        nz("thehits", "The Hits", "Hits", "HE-AAC · 64 kbps",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_12AAC_SC");
        nz("hauraki", "Radio Hauraki", "Rock", "HE-AAC · 64 kbps",
                "https://playerservices.streamtheworld.com/api/livestream-redirect/NZME_04AAC_SC");
        nz("maifm", "Mai FM", "Hip-hop & R&B", "AAC · 130 kbps", "https://mediaworks.streamguys1.com/mai_net_icy");
        nz("georgefm", "George FM", "Dance & electronic", "AAC · 130 kbps", "https://mediaworks.streamguys1.com/george_net_icy");
        nz("theedge", "The Edge", "Hits", "AAC · 130 kbps", "https://mediaworks.streamguys1.com/edge_net_icy");
        nz("therock", "The Rock", "Rock", "AAC · 130 kbps", "https://mediaworks.streamguys1.com/rock_net_icy");
        nz("morefm", "More FM", "Adult hits", "AAC · 130 kbps", "https://mediaworks.streamguys1.com/more_net_icy");
        nz("thebreeze", "The Breeze", "Easy listening", "AAC · 130 kbps", "https://mediaworks.streamguys1.com/breeze_net_icy");
        nz("thesound", "The Sound", "Classic rock", "AAC · 130 kbps", "https://mediaworks.streamguys1.com/sound_net_icy");
        nz("magic", "Magic", "Oldies & classic hits", "AAC · 130 kbps", "https://mediaworks.streamguys1.com/magic_net_icy");

        // FIP and its themed channels, 192 kbps AAC. No ICY metadata; song
        // titles and covers come from Radio France's livemeta feed (by id).
        fip("fip", "FIP", "Eclectic", "fip", 7);
        fip("fiprock", "FIP Rock", "Rock", "fiprock", 64);
        fip("fipjazz", "FIP Jazz", "Jazz", "fipjazz", 65);
        fip("fipgroove", "FIP Groove", "Funk, soul & groove", "fipgroove", 66);
        fip("fipworld", "FIP Monde", "World music", "fipworld", 69);
        fip("fipnew", "FIP Nouveautés", "New releases", "fipnouveautes", 70);
        fip("fipreggae", "FIP Reggae", "Reggae", "fipreggae", 71);
        fip("fipelectro", "FIP Electro", "Electronic", "fipelectro", 74);
        fip("fipmetal", "FIP Metal", "Metal", "fipmetal", 77);

        // The directory has logos for most of these; fetch them off the startup path
        CompletableFuture.runAsync(() -> {
            for (Curated c : curated) {
                if (c.info().logo() != null)
                    continue;
                RadioBrowser.Station s = browser.byUrl(c.url());
                if (s == null || s.favicon == null) // often listed under the other scheme
                    s = browser.byUrl(c.url().startsWith("https:") ? "http:" + c.url().substring(6) : "https:" + c.url().substring(5));
                if (s != null && s.favicon != null)
                    bot.getRadioMetadata().setLogoIfMissing(c.url(), s.favicon);
            }
        });
    }

    private void nz(String key, String name, String genre, String format, String url) {
        add(new Curated(key, "nz", url, new RadioMetadata.StationInfo(name, null, null, format, NZ, genre, null)));
    }

    private void fip(String key, String name, String genre, String mount, int livemetaId) {
        add(new Curated(key, "fip", "https://icecast.radiofrance.fr/" + mount + "-hifi.aac",
                new RadioMetadata.StationInfo(name, "https://api.radiofrance.fr/livemeta/pull/" + livemetaId, FIP_LOGO,
                        "AAC · 192 kbps", FIP_ORIGIN, genre, FIP_HOME)));
    }

    private void add(Curated c) {
        curated.add(c);
        byKey.put(c.key(), c);
        bot.getRadioMetadata().register(c.url(), c.info());
    }

    // ------------------------------------------------------------------ the picker

    /** The station picker: curated dropdowns plus worldwide search, popular and random. */
    public MessageCreateData panel(Guild guild) {
        StringBuilder desc = new StringBuilder("Pick a station below, or search **~50,000 stations worldwide** "
                + "by name or genre.");
        AudioHandler handler = guild == null ? null : (AudioHandler) guild.getAudioManager().getSendingHandler();
        String onAir = handler == null ? null : bot.getRadioMetadata().getLabel(handler.getPlayer().getPlayingTrack());
        if (onAir != null)
            desc.append("\n\n**On air:** ").append(FormatUtil.filter(onAir));
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("📻 Radio")
                .setDescription(desc)
                .setFooter("Tip: ?radio <name> plays any station directly, e.g. ?radio kexp or ?radio jazz");
        if (guild != null)
            eb.setColor(guild.getSelfMember().getColor());
        return new MessageCreateBuilder()
                .setEmbeds(eb.build())
                .setComponents(
                        ActionRow.of(curatedMenu("nz", "🇳🇿 New Zealand stations")),
                        ActionRow.of(curatedMenu("fip", "🇫🇷 FIP from Paris — 9 channels, 192 kbps")),
                        ActionRow.of(
                                Button.primary(PlayerControls.RADIO_SEARCH, "Search worldwide").withEmoji(Emoji.fromUnicode("🔎")),
                                Button.secondary(POPULAR, "Popular worldwide").withEmoji(Emoji.fromUnicode("🌍")),
                                Button.secondary(RANDOM, "Surprise me").withEmoji(Emoji.fromUnicode("🎲"))))
                .build();
    }

    private StringSelectMenu curatedMenu(String group, String placeholder) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(PICK + ":" + group).setPlaceholder(placeholder);
        for (Curated c : curated)
            if (c.group().equals(group))
                menu.addOptions(SelectOption.of(c.info().name(), c.key())
                        .withDescription(c.info().genre() + " · " + c.info().format()));
        return menu.build();
    }

    /** Worldwide results as a list and a dropdown to play one. */
    public MessageCreateData results(String title, List<RadioBrowser.Station> stations) {
        StringBuilder list = new StringBuilder();
        StringSelectMenu.Builder menu = StringSelectMenu.create(WORLD_PICK).setPlaceholder("Pick a station to play");
        for (int i = 0; i < stations.size(); i++) {
            RadioBrowser.Station s = stations.get(i);
            String flag = RadioBrowser.flag(s.countryCode);
            List<String> details = new ArrayList<>();
            if (s.format() != null)
                details.add(s.format());
            if (s.genre() != null)
                details.add(s.genre());
            list.append("`").append(i + 1).append(".` ").append(flag == null ? "🌍" : flag)
                    .append(" **").append(FormatUtil.filter(s.name)).append("**");
            if (!details.isEmpty())
                list.append(" — ").append(FormatUtil.filter(String.join(" · ", details)));
            list.append('\n');
            SelectOption option = SelectOption.of(truncate(s.name, 100), WORLD_PREFIX + s.uuid)
                    .withDescription(truncate((s.country.isEmpty() ? "" : s.country + " · ")
                            + String.join(" · ", details), 100));
            menu.addOptions(flag == null ? option : option.withEmoji(Emoji.fromUnicode(flag)));
        }
        return new MessageCreateBuilder()
                .setEmbeds(new EmbedBuilder().setTitle(title).setDescription(truncate(list.toString(), 4000)).build())
                .setComponents(ActionRow.of(menu.build()), ActionRow.of(
                        Button.secondary(PlayerControls.RADIO_SEARCH, "Search again").withEmoji(Emoji.fromUnicode("🔎"))))
                .build();
    }

    public Modal searchModal() {
        TextInput input = TextInput.create(SEARCH_INPUT, TextInputStyle.SHORT)
                .setPlaceholder("e.g. KEXP, Radio Paradise, jazz, synthwave")
                .setRequired(true)
                .setMaxLength(100)
                .build();
        return Modal.create(SEARCH_MODAL, "Search radio stations worldwide")
                .addComponents(Label.of("Station name or genre", input))
                .build();
    }

    public List<RadioBrowser.Station> search(String query) {
        return browser.search(query, 10);
    }

    public List<RadioBrowser.Station> popular() {
        return browser.popular(20);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------ autocomplete

    /**
     * /radio autocomplete: browse/random, curated stations matching what's
     * typed, then (once a few letters are in) worldwide matches. Returns
     * value -> label.
     */
    public Map<String, String> autocomplete(String typed) {
        String query = typed.trim().toLowerCase(Locale.ROOT);
        Map<String, String> choices = new LinkedHashMap<>();
        if (query.isEmpty() || "list".startsWith(query) || "browse".startsWith(query))
            choices.put("list", "📻 Browse stations & search worldwide");
        if (query.isEmpty() || "random".startsWith(query) || "surprise".startsWith(query))
            choices.put("random", "🎲 Surprise me");
        for (Curated c : curated) {
            String name = c.info().name();
            if (c.key().contains(squash(query)) || name.toLowerCase(Locale.ROOT).contains(query)
                    || c.info().genre().toLowerCase(Locale.ROOT).contains(query))
                choices.put(c.key(), (c.group().equals("nz") ? "🇳🇿 " : "🇫🇷 ")
                        + name + " — " + c.info().genre());
        }
        if (query.length() >= 3) {
            // Discord drops autocomplete answers after 3 seconds
            try {
                List<RadioBrowser.Station> world = CompletableFuture
                        .supplyAsync(() -> browser.search(query, 15))
                        .get(2200, TimeUnit.MILLISECONDS);
                for (RadioBrowser.Station s : world) {
                    String flag = RadioBrowser.flag(s.countryCode);
                    String format = s.format();
                    choices.putIfAbsent(WORLD_PREFIX + s.uuid, (flag == null ? "🌍 " : flag + " ") + s.name
                            + (format == null ? "" : " — " + format));
                }
            } catch (Exception ignored) {
                // slow or unreachable: offer the curated matches only
            }
        }
        return choices;
    }

    /** Curated key for typed text: "the edge" -> "theedge", "FIP Jazz", ... */
    private String curatedKey(String args) {
        String squashed = squash(args);
        if (byKey.containsKey(squashed))
            return squashed;
        for (Curated c : curated)
            if (c.info().name().equalsIgnoreCase(args) || squash(c.info().name().toLowerCase(Locale.ROOT)).equals(squashed))
                return c.key();
        return null;
    }

    private static String squash(String s) {
        return s.replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------------ the command

    @Override
    public void doCommand(CommandEvent event) {
        String raw = event.getArgs().trim();
        String args = raw.toLowerCase(Locale.ROOT);

        if (args.isEmpty() || args.equals("list") || args.equals("browse")) {
            event.reply(panel(event.getGuild()));
            return;
        }
        if (args.equals("skip") || args.equals("next")) {
            skip(event);
            return;
        }
        if (args.equals("random") || args.equals("surprise")) {
            event.reply("🎲 Finding something good...", m -> CompletableFuture
                    .supplyAsync(browser::random)
                    .thenAccept(s -> {
                        if (s == null)
                            m.editMessage(event.getClient().getWarning() + " The station directory isn't answering; try again shortly.").queue();
                        else
                            playWorld(event, m, s, null);
                    }));
            return;
        }

        String key = curatedKey(args);
        if (key != null) {
            Curated c = byKey.get(key);
            play(event, null, c.url(), c.info().name(), key, null);
            return;
        }

        // An exact pick from a dropdown or /radio autocomplete
        if (args.startsWith(WORLD_PREFIX)) {
            String uuid = raw.substring(WORLD_PREFIX.length());
            event.reply(event.getClient().getSuccess() + " Tuning in...", m -> CompletableFuture
                    .supplyAsync(() -> browser.byUuid(uuid))
                    .thenAccept(s -> {
                        if (s == null)
                            m.editMessage(event.getClient().getWarning() + " That station has gone from the directory.").queue();
                        else
                            playWorld(event, m, s, null);
                    }));
            return;
        }

        event.reply("🔎 Searching radio stations worldwide for **" + FormatUtil.filter(raw) + "**...",
                m -> CompletableFuture
                        .supplyAsync(() -> browser.search(raw, 5))
                        .thenAccept(results -> {
                            if (results.isEmpty()) {
                                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                                        + " No radio station found for **" + raw + "**. Type `"
                                        + event.getClient().getPrefix() + name + "` to browse.")).queue();
                                return;
                            }
                            playWorld(event, m, results.get(0), results.subList(1, results.size()));
                        }));
    }

    private void skip(CommandEvent event) {
        AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
        if (handler == null || !bot.getRadioMetadata().isRadio(handler.getPlayer().getPlayingTrack())) {
            event.replyError("No radio station is currently playing. Use `" + event.getClient().getPrefix() + name
                    + "` to pick one.");
            return;
        }
        // Next in the curated order; from a worldwide station, start at the top
        String currentKey = currentStationByGuild.get(event.getGuild().getIdLong());
        int index = 0;
        for (int i = 0; currentKey != null && i < curated.size(); i++)
            if (curated.get(i).key().equals(currentKey))
                index = (i + 1) % curated.size();
        Curated next = curated.get(index);
        play(event, null, next.url(), next.info().name(), next.key(), null);
    }

    /** Plays a radio-browser station, mentioning the other matches in case it's the wrong one. */
    private void playWorld(CommandEvent event, Message m, RadioBrowser.Station station, List<RadioBrowser.Station> others) {
        // Registering makes it a radio station to the rest of the bot: song
        // titles, status, the live card, and being replaced by anything queued
        bot.getRadioMetadata().register(station.url, station.toInfo());
        String extra = null;
        if (others != null && !others.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (RadioBrowser.Station o : others)
                names.add("`" + o.label() + "`");
            extra = "Not it? Also found " + FormatUtil.filter(String.join(", ", names))
                    + ". Use the exact name, or **Search worldwide** below.";
        }
        play(event, m, station.url, station.label(), null, extra);
    }

    /**
     * Loads a station and turns the reply into its live card. Replies
     * "Tuning in..." first unless a message to edit is given; curated
     * stations pass their key for skip.
     */
    private void play(CommandEvent event, Message m, String url, String displayName, String key, String extra) {
        if (m == null) {
            event.reply(event.getClient().getSuccess() + " Tuning in to **" + FormatUtil.filter(displayName) + "**...",
                    msg -> play(event, msg, url, displayName, key, extra));
            return;
        }
        bot.getPlayerManager().loadItemOrdered(event.getGuild(), url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                AudioHandler handler = (AudioHandler) event.getGuild().getAudioManager().getSendingHandler();
                // addTrack replaces a station that is already playing
                handler.addTrack(new QueuedTrack(track, RequestMetadata.fromResultHandler(track, event)));
                if (key == null)
                    currentStationByGuild.remove(event.getGuild().getIdLong());
                else
                    currentStationByGuild.put(event.getGuild().getIdLong(), key);
                // Song titles feed the card, bot status, voice channel status and ?np
                bot.getRadioMetadata().start(event.getGuild().getIdLong(), url);

                PlayerControls controls = bot.getPlayerControls();
                m.editMessage(controls.editRadio(event.getGuild(), track, extra))
                        .queue(msg -> controls.registerRadioCard(msg, extra));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (!playlist.getTracks().isEmpty())
                    trackLoaded(playlist.getTracks().get(0));
            }

            @Override
            public void noMatches() {
                m.editMessage(FormatUtil.filter(event.getClient().getWarning()
                        + " **" + displayName + "** isn't streaming anything I can play right now.")).queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                m.editMessage(FormatUtil.filter(event.getClient().getError() + " Couldn't tune in to **"
                        + displayName + "**: " + exception.getMessage())).queue();
            }
        });
    }
}
