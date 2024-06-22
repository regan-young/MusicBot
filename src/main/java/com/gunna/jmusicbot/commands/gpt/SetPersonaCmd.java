package com.gunna.jmusicbot.commands.gpt;

import java.util.List;
import java.util.stream.Collectors;

import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.GPTCommand;
import com.jagrosh.jmusicbot.settings.Settings;

public class SetPersonaCmd extends GPTCommand {
	public SetPersonaCmd(Bot bot) {
		super(bot);
		this.name = "persona";
		this.help = "Sets the ChatGPT Prompt to a predefined set of personas.";
		this.arguments = "Available personas are: " + bot.getPromptLoader().getPersonaNames().stream()
				.map(el -> "`" + el + "`").collect(Collectors.toList());
		this.aliases = bot.getConfig().getAliases(this.name);
	}

	@Override
	protected void execute(CommandEvent event) {
		{
			Settings s = event.getClient().getSettingsFor(event.getGuild());
			if (event.getArgs().isEmpty()) {
				List<String> xx = bot.getPromptLoader().getPersonaNames().stream().map(el -> "`" + el + "`")
						.collect(Collectors.toList());
				String personas = String.join(", ", xx);
				event.reply(":robot: :x:" + " Please include a persona name. Available options are: " + personas);
			} else {
				s.setPersona(event.getArgs());
				String prompt = bot.getPromptLoader().getPrompt(event.getArgs());
				String truncated = prompt.length() > 1500 ? prompt.substring(0, 1500) + "..." : prompt;
				event.reply(":robot: :speech_balloon:" + " Persona set as " + event.getArgs() + "```" + truncated
						+ "```\n");
			}
		}
	}
}