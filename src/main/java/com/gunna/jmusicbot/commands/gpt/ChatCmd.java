package com.gunna.jmusicbot.commands.gpt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.jagrosh.jdautilities.command.CommandEvent;
import com.jagrosh.jmusicbot.Bot;
import com.jagrosh.jmusicbot.commands.GPTCommand;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.OpenAiService;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;

public class ChatCmd extends GPTCommand {
	public ChatCmd(Bot bot) {
		super(bot);
		this.name = "chat";
		this.help = "Passes query to ChatGPT and returns the result";
		this.aliases = bot.getConfig().getAliases(this.name);
	}

	@Override
	protected void execute(CommandEvent event) {
		if (event.getArgs().isEmpty()) {
			event.replyError("Please include a query.");
			return;
		}

		List<ChatMessage> history = new ArrayList<ChatMessage>();
		if(event.getMessage().getMessageReference() != null) {
			history = generateMsgThread(event);
		}

		String persona = bot.getSettingsManager().getSettings(event.getGuild()).getPersona();
		String personaPrompt = bot.getPromptLoader().getPrompt(persona);

		ChatMessage personaP = new ChatMessage();
		personaP.setRole("user");
		personaP.setContent(personaPrompt);

		ChatMessage userP = new ChatMessage();
		userP.setRole("user");
		userP.setContent(event.getArgs());

		List<ChatMessage> prompt = new ArrayList<ChatMessage>();
		prompt.add(personaP);

		List<ChatMessage> messages = Stream.concat(prompt.stream(), history.stream())
				.collect(Collectors.toList());
		messages.add(userP);

		ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
		String json;
		try {
			json = ow.writeValueAsString(messages);
			LoggerFactory.getLogger("Chat").info(json);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
		}

		try {
			OpenAiService service = new OpenAiService("", Duration.ofMinutes(1));
			ChatCompletionRequest completionRequest = ChatCompletionRequest.builder().messages(messages)
					.model("gpt-3.5-turbo").build();
			ChatCompletionResult response = service.createChatCompletion(completionRequest);
			LoggerFactory.getLogger("Chat").info(response.toString());

			String responseText = ":robot: :speech_balloon:" + response.getChoices().get(0).getMessage().getContent();
			ArrayList<String> splitMsg = CommandEvent.splitMessage(responseText);
			for(int i=0; i<3 && i<splitMsg.size(); i++)
			{
				event.getMessage().reply(splitMsg.get(i)).queue();
			}
		} catch (Exception e) {
			event.getMessage().reply(":cross: Something went wrong").queue();
			LoggerFactory.getLogger("Chat").info("E", e);
		}
	}

	public List<ChatMessage> generateMsgThread(CommandEvent event) {
		List<ChatMessage> resultThread = new ArrayList<ChatMessage>();
		Message processMsg = event.getMessage();
		MessageHistory messageHistory = event.getChannel().getHistoryAround(event.getMessage().getId(), 50).complete();

		for (int i = 0; i < 10; i++) {
			Message nextReference = processMsg.getReferencedMessage();

			if (nextReference != null) {
				List<Message> matched = messageHistory.getRetrievedHistory().stream()
						.filter(msg -> msg.getId().equals(nextReference.getId()))
						.collect(Collectors.toList());

				if (matched.size() > 0) {
					Message matchMsg = matched.get(0);
					String role = matchMsg.getAuthor().getId().equals(event.getSelfUser().getId()) ? "assistant" : "user";
					String content = matchMsg.getContentDisplay();

					ChatMessage refChat = new ChatMessage();
					refChat.setRole(role);
					refChat.setContent(content);

					resultThread.add(refChat);
					processMsg = matchMsg;
				}
			}
			else
			{
				break;
			}
		}

		Collections.reverse(resultThread);
		return resultThread;
	}

	public void handleMsgHistory(MessageHistory messageHistory) {

	}
}

