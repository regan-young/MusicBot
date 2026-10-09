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
    // Slash commands with a named option instead of the generic "input"
    private static final String PLAY_QUERY = "query", PLAY_FILE = "file", RADIO_STATION = "station",
            MIX_SONG = "song", GENERIC_INPUT = "input";

    private final Bot bot;
    private final CommandClient client;

    public InteractionListener(Bot bot, CommandClient client) {
        this.bot = bot;
        this.client = client;
    }

    // ------------------------------------------------------------------ registration

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        List<SlashCommandData> data = new ArrayList<>();
        for (Command command : client.getCommands()) {
            if (command.isOwnerCommand() || command.isHidden())
                continue;
            data.add(slashData(command));
        }
        data.add(Commands.slash("help", "shows what I can do").setContexts(InteractionContextType.GUILD));
        event.getJDA().updateCommands().addCommands(data).queue(
                cmds -> LOG.info("Registered {} slash commands", cmds.size()),
                t -> LOG.error("Could not register slash commands (is the applications.commands scope granted?)", t));
    }

    private SlashCommandData slashData(Command command) {
        String help = command.getHelp() == null || command.getHelp().isBlank() ? command.getName() : command.getHelp();
        SlashCommandData data = Commands.slash(command.getName(), truncate(help, 100))
                .setContexts(InteractionContextType.GUILD);
        switch (command.getName()) {
            case "play":
                data.addOptions(
                        new OptionData(OptionType.STRING, PLAY_QUERY, "song title, URL, or \"playlist <name>\""),
                        new OptionData(OptionType.ATTACHMENT, PLAY_FILE, "an audio file to play"));
                break;
            case "radio":
                data.addOptions(new OptionData(OptionType.STRING, RADIO_STATION, "station name, list, or skip")
                        .setAutoComplete(true));
                break;
            case "mix":
                data.addOptions(new OptionData(OptionType.STRING, MIX_SONG,
                        "song to build a mix around (default: what's playing)"));
                break;
            default:
                if (command.getArguments() != null && !command.getArguments().isBlank())
                    data.addOptions(new OptionData(OptionType.STRING, GENERIC_INPUT, truncate(command.getArguments(), 100)));
        }
        return data;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------ slash commands

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("I only work in servers.").setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue();
        InteractionCommandEvent commandEvent = new InteractionCommandEvent(event, slashArgs(event), client, false);
        try {
            if (event.getName().equals("help")) {
                HelpMessage.reply(commandEvent);
            } else {
                Command command = findCommand(event.getName());
                if (command == null) {
                    commandEvent.replyError("That command no longer exists.");
                    return;
                }
                command.run(commandEvent);
            }
        } catch (Exception e) {
            LOG.error("Slash command /{} failed", event.getName(), e);
            commandEvent.replyError("Something went wrong running that command.");
        }
        commandEvent.cleanUpIfUnanswered();
    }

    private static String slashArgs(SlashCommandInteractionEvent event) {
        String args = firstString(event, PLAY_QUERY, RADIO_STATION, MIX_SONG, GENERIC_INPUT);
        if (args.isEmpty()) {
            OptionMapping file = event.getOption(PLAY_FILE);
            if (file != null)
                args = file.getAsAttachment().getUrl();
        }
        return args;
    }

    private static String firstString(SlashCommandInteractionEvent event, String... names) {
        for (String name : names) {
            OptionMapping option = event.getOption(name);
            if (option != null)
                return option.getAsString().trim();
        }
        return "";
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        if (!event.getName().equals("radio") || !event.getFocusedOption().getName().equals(RADIO_STATION))
            return;
        Command command = findCommand("radio");
        if (!(command instanceof RadioCmd))
            return;
        String typed = event.getFocusedOption().getValue().toLowerCase();
        List<net.dv8tion.jda.api.interactions.commands.Command.Choice> choices = ((RadioCmd) command)
                .getStationChoices().entrySet().stream()
                .filter(e -> e.getKey().contains(typed) || e.getValue().toLowerCase().contains(typed))
                .limit(25)
                .map(e -> new net.dv8tion.jda.api.interactions.commands.Command.Choice(e.getValue(), e.getKey()))
                .collect(Collectors.toList());
        event.replyChoices(choices).queue();
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
        String commandName = null;
        String args = "";
        switch (id) {
            case PlayerControls.PAUSE:
                // ?play with no arguments resumes a paused player
                commandName = handler.getPlayer().isPaused() ? "play" : "pause";
                break;
            case PlayerControls.SKIP:
                // DJs skip outright; everyone else votes, as with ?skip
                boolean dj = DJCommand.checkDJPermission(new InteractionCommandEvent(event, "", client, true));
                commandName = dj ? "forceskip" : "skip";
                break;
            case PlayerControls.STOP:
                commandName = "stop";
                break;
            case PlayerControls.REPEAT:
                RepeatMode mode = bot.getSettingsManager().getSettings(guild).getRepeatMode();
                commandName = "repeat";
                args = mode == RepeatMode.OFF ? "all" : mode == RepeatMode.ALL ? "single" : "off";
                break;
        }
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
