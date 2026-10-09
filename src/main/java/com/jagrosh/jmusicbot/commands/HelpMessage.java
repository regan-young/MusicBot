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
package com.jagrosh.jmusicbot.commands;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.InteractionListener;
import com.jagrosh.jmusicbot.settings.Settings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The help command. Replaces chewtils' default, which only sends help by DM
 * ("Help cannot be sent because you are blocking Direct Messages") as one
 * plain-text wall. This replies in the channel with an embed grouped by
 * category, and is shared by ?help and /help.
 */
public final class HelpMessage {
    private static final Map<String, String> CATEGORY_TITLES = new LinkedHashMap<>();

    static {
        CATEGORY_TITLES.put("Music", "🎵 Music");
        CATEGORY_TITLES.put("DJ", "🎚️ Playback (anyone in the voice channel)");
        CATEGORY_TITLES.put("General", "ℹ️ General");
        CATEGORY_TITLES.put("Admin", "🛠️ Admin (Manage Server)");
        CATEGORY_TITLES.put("Owner", "👑 Owner");
    }

    private HelpMessage() {
    }

    public static void reply(CommandEvent event) {
        event.reply(build(event));
    }

    private static MessageEmbed build(CommandEvent event) {
        String prefix = event.getClient().getTextualPrefix();
        if (event.getGuild() != null) {
            Settings settings = event.getClient().getSettingsFor(event.getGuild());
            if (settings != null && settings.getPrefix() != null)
                prefix = settings.getPrefix();
        }

        Map<String, List<String>> lines = new LinkedHashMap<>();
        CATEGORY_TITLES.keySet().forEach(k -> lines.put(k, new ArrayList<>()));
        for (Command command : event.getClient().getCommands()) {
            if (command.isHidden())
                continue;
            String category = command.getCategory() == null ? "General" : command.getCategory().getName();
            if (category.equals("Owner") && !event.isOwner())
                continue;
            lines.computeIfAbsent(category, k -> new ArrayList<>()).add(line(prefix, command.getName(), command));
            for (Command child : command.getChildren())
                if (!child.isHidden())
                    lines.get(category).add(line(prefix, command.getName() + " " + child.getName(), child));
        }

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle(event.getSelfUser().getName() + " commands")
                .setDescription("Type `" + prefix + "command`. The everyday ones are also slash commands (shown"
                        + " after them). The buttons on my replies pause, skip, stop and repeat whatever is playing.");
        if (event.getSelfMember() != null)
            eb.setColor(event.getSelfMember().getColor());
        for (Map.Entry<String, List<String>> e : lines.entrySet()) {
            if (e.getValue().isEmpty())
                continue;
            addField(eb, CATEGORY_TITLES.getOrDefault(e.getKey(), e.getKey()), e.getValue());
        }
        return eb.build();
    }

    private static String line(String prefix, String name, Command command) {
        String args = command.getArguments() == null || command.getArguments().isEmpty()
                ? "" : " " + command.getArguments();
        String slash = name.contains(" ") ? null : InteractionListener.slashFor(name);
        return "`" + prefix + name + args + "`" + (slash == null ? "" : " · `" + slash + "`")
                + " — " + command.getHelp();
    }

    /** Embed fields hold 1024 characters; continue long categories in untitled fields. */
    private static void addField(EmbedBuilder eb, String title, List<String> lines) {
        StringBuilder value = new StringBuilder();
        String name = title;
        for (String line : lines) {
            if (value.length() + line.length() + 1 > MessageEmbed.VALUE_MAX_LENGTH) {
                eb.addField(name, value.toString(), false);
                value.setLength(0);
                name = "​";
            }
            if (value.length() > 0)
                value.append('\n');
            value.append(line);
        }
        eb.addField(name, value.toString(), false);
    }
}
