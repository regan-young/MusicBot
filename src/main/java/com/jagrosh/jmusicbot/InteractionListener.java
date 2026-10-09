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
package com.jagrosh.jmusicbot;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandClient;
import com.jagrosh.jmusicbot.audio.AudioHandler;
import com.jagrosh.jmusicbot.audio.PlayerControls;
import com.jagrosh.jmusicbot.audio.QueuedTrack;
import com.jagrosh.jmusicbot.commands.DJCommand;
import com.jagrosh.jmusicbot.commands.HelpMessage;
import com.jagrosh.jmusicbot.commands.InteractionCommandEvent;
import com.jagrosh.jmusicbot.commands.music.RadioCmd;
import com.jagrosh.jmusicbot.settings.RepeatMode;
import com.jagrosh.jmusicbot.utils.FormatUtil;
import com.jagrosh.jmusicbot.utils.TimeUtil;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Slash commands and player buttons. Both run the existing prefix commands
 * through {@link InteractionCommandEvent}, so ?play and /play share one
 * implementation, permission checks included.
 */
public class InteractionListener extends ListenerAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(InteractionListener.class);
    /**
     * The slash commands: the everyday ones only, each mirroring its ?command.
     * The main argument comes first and is required where the command needs
     * one, so whatever is typed after "/play " goes straight into it. Every
     * other command stays prefix-only.
     */
    private static final List<Slash> SLASH = List.of(
            new Slash("play", "play", "play a song or add it to the queue",
                    new OptionData(OptionType.STRING, "song", "song title or URL", true)),
            new Slash("radio", "radio", "play a radio station: NZ favourites or any station worldwide",
                    new OptionData(OptionType.STRING, "station", "station name, or \"list\"", true).setAutoComplete(true)),
            new Slash("mix", "mix", "queue songs like a song (or like what's playing)",
                    new OptionData(OptionType.STRING, "song", "song to build the mix around", false)),
            new Slash("skip", null, "skip the song (a vote, unless you're a DJ or requested it)", null),
            new Slash("pause", null, "pause or resume", null),
            new Slash("stop", "stop", "stop playing and clear the queue", null),
            new Slash("queue", null, "what's playing and what's next", null),
            new Slash("np", "nowplaying", "the current song, with controls", null),
            new Slash("help", null, "what I can do", null));

    /** A slash command; command is the ?command it runs (null: handled here). */
    private record Slash(String name, String command, String description, OptionData option) {
        SlashCommandData data() {
            SlashCommandData data = Commands.slash(name, description).setContexts(InteractionContextType.GUILD);
            return option == null ? data : data.addOptions(option);
        }
    }

    /** "/np" for ?nowplaying etc.; null if the command is prefix-only. */
    public static String slashFor(String commandName) {
        for (Slash s : SLASH)
            if (commandName.equals(s.command()) || (s.command() == null && commandName.equals(s.name())))
                return "/" + s.name();
        return null;
    }

    private final Bot bot;
    private final CommandClient client;

    public InteractionListener(Bot bot, CommandClient client) {
        this.bot = bot;
        this.client = client;
    }

    // ------------------------------------------------------------------ registration

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        event.getJDA().updateCommands()
                .addCommands(SLASH.stream().map(Slash::data).collect(Collectors.toList()))
                .queue(cmds -> LOG.info("Registered {} slash commands", cmds.size()),
                        t -> LOG.error("Could not register slash commands (is the applications.commands scope granted?)", t));
    }

    // ------------------------------------------------------------------ slash commands

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("I only work in servers.").setEphemeral(true).queue();
            return;
        }
        Slash slash = SLASH.stream().filter(s -> s.name().equals(event.getName())).findFirst().orElse(null);
        if (slash == null) {
            event.reply("That command no longer exists.").setEphemeral(true).queue();
            return;
        }
        Guild guild = event.getGuild();
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);
        if (slash.name().equals("queue")) {
            event.reply(queueText(guild, handler)).queue();
            return;
        }

        event.deferReply().queue();
        String args = "";
        if (slash.option() != null) {
            OptionMapping option = event.getOption(slash.option().getName());
            if (option != null)
                args = option.getAsString().trim();
        }
        String commandName = slash.command();
        if (slash.name().equals("skip") || slash.name().equals("pause")) {
            String[] resolved = playerCommand(slash.name(), event, handler, guild);
            commandName = resolved[0];
            args = resolved[1];
        }

        InteractionCommandEvent commandEvent = new InteractionCommandEvent(event, args, client, false);
        try {
            if (slash.name().equals("help")) {
                HelpMessage.reply(commandEvent);
            } else {
                Command command = findCommand(commandName);
                if (command == null)
                    commandEvent.replyError("That command no longer exists.");
                else
                    command.run(commandEvent);
            }
        } catch (Exception e) {
            LOG.error("Slash command /{} failed", event.getName(), e);
            commandEvent.replyError("Something went wrong running that command.");
        }
        commandEvent.cleanUpIfUnanswered();
    }

    /**
     * The ?command (and its args) behind a player action shared by slash
     * commands and buttons: pause toggles (?play with no args resumes), skip
     * is immediate for DJs and a vote otherwise, repeat cycles off/all/single.
     */
    private String[] playerCommand(String action, IReplyCallback interaction, AudioHandler handler, Guild guild) {
        switch (action) {
            case "pause":
                return new String[] { handler.getPlayer().isPaused() ? "play" : "pause", "" };
            case "skip":
                boolean dj = DJCommand.checkDJPermission(new InteractionCommandEvent(interaction, "", client, true));
                return new String[] { dj ? "forceskip" : "skip", "" };
            case "stop":
                return new String[] { "stop", "" };
            case "repeat":
                RepeatMode mode = bot.getSettingsManager().getSettings(guild).getRepeatMode();
                return new String[] { "repeat", mode == RepeatMode.OFF ? "all" : mode == RepeatMode.ALL ? "single" : "off" };
            default:
                return null;
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("radio") || !event.getFocusedOption().getName().equals("station"))
            return;
        Command command = findCommand("radio");
        if (!(command instanceof RadioCmd))
            return;
        List<net.dv8tion.jda.api.interactions.commands.Command.Choice> choices = ((RadioCmd) command)
                .autocomplete(event.getFocusedOption().getValue()).entrySet().stream()
                .limit(25)
                .map(e -> new net.dv8tion.jda.api.interactions.commands.Command.Choice(truncate(e.getValue(), 100), e.getKey()))
                .collect(Collectors.toList());
        event.replyChoices(choices).queue(null, t -> { });
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------ buttons

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PlayerControls.ID_PREFIX) || !event.isFromGuild())
            return;
        Guild guild = event.getGuild();
        AudioHandler handler = bot.getPlayerManager().setUpHandler(guild);

        if (id.equals(PlayerControls.QUEUE)) {
            event.reply(queueText(guild, handler)).setEphemeral(true).queue();
            return;
        }

        // After a restart, older messages may still carry buttons; adopt the
        // one that was used so only it stays live.
        bot.getPlayerControls().register(event.getMessage());

        event.deferReply(true).queue();
        String[] resolved = playerCommand(id.substring(PlayerControls.ID_PREFIX.length()), event, handler, guild);
        String commandName = resolved == null ? null : resolved[0];
        String args = resolved == null ? "" : resolved[1];
        InteractionCommandEvent commandEvent = new InteractionCommandEvent(event, args, client, true);
        Command command = commandName == null ? null : findCommand(commandName);
        if (command == null) {
            commandEvent.replyError("That button is no longer supported.");
            return;
        }
        try {
            command.run(commandEvent);
        } catch (Exception e) {
            LOG.error("Player button {} failed", id, e);
            commandEvent.replyError("Something went wrong.");
        }
        commandEvent.cleanUpIfUnanswered();
        bot.getPlayerControls().refresh(guild);
    }

    private String queueText(Guild guild, AudioHandler handler) {
        AudioTrack playing = handler.getPlayer().getPlayingTrack();
        if (playing == null)
            return bot.getConfig().getWarning() + " Nothing is playing.";
        StringBuilder sb = new StringBuilder();
        String radio = bot.getRadioMetadata().getLabel(playing);
        sb.append(handler.getStatusEmoji()).append(" **")
                .append(radio != null ? radio : FormatUtil.filter(playing.getInfo().title)).append("**");
        if (radio == null)
            sb.append(" `[").append(TimeUtil.formatTime(playing.getPosition())).append("/")
                    .append(TimeUtil.formatTime(playing.getDuration())).append("]`");
        List<QueuedTrack> queue = handler.getQueue().getList();
        if (queue.isEmpty()) {
            sb.append("\n\nThe queue is empty.");
        } else {
            sb.append("\n\n**Up next:**");
            for (int i = 0; i < queue.size() && i < 10; i++)
                sb.append("\n`").append(i + 1).append(".` ").append(queue.get(i).toString());
            if (queue.size() > 10)
                sb.append("\n…and ").append(queue.size() - 10).append(" more. Use `/queue` to see them all.");
        }
        return sb.length() > 2000 ? sb.substring(0, 1997) + "…" : sb.toString();
    }

    private Command findCommand(String name) {
        for (Command command : client.getCommands())
            if (command.getName().equalsIgnoreCase(name))
                return command;
        return null;
    }
}
