package com.jagrosh.jmusicbot.commands;

import com.jagrosh.jdautilities.command.Command;
import com.jagrosh.jmusicbot.Bot;

import net.dv8tion.jda.api.Permission;

public abstract class GPTCommand extends Command
{
    protected Bot bot;

	public GPTCommand(Bot bot) {
        this.bot = bot;
        this.guildOnly = true;
        this.category = new Category("GPT", event -> 
        {
            if(event.getAuthor().getId().equals(event.getClient().getOwnerId()))
                return true;
            if(event.getGuild()==null)
                return true;
            return event.getMember().hasPermission(Permission.MANAGE_SERVER);
        });
        this.guildOnly = true;
    }
}
