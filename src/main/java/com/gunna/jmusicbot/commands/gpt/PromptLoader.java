package com.gunna.jmusicbot.commands.gpt;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;

public class PromptLoader
{
    public List<Prompt> prompts;
    
    public PromptLoader()
    {
    }
    
    public class Prompt
    {
    	public String name;
    	public String prompt;
    	
        public Prompt(String name, String prompt) {
        	this.name = name;
        	this.prompt = prompt;
        }
    }
    
    public void load()
    {
    	List<Prompt> prompts = new ArrayList<>();
        File f = new File("prompts.txt");
        if (f.exists()){
            InputStream is;
            String jsonTxt;
			try {
				is = new FileInputStream("prompts.txt");
				jsonTxt = IOUtils.toString(is, "UTF-8");
				JSONArray json = new JSONArray(jsonTxt);
	            for (int i = 0 ; i < json.length(); i++) {
	            	JSONObject obj = json.getJSONObject(i);
	            	Prompt ppp = new Prompt(obj.getString("name"), obj.getString("prompt"));
	            	prompts.add(ppp);
	            }
	            
			} catch (Exception e) { }
        } else {
        	prompts.add(new Prompt("default", ""));
        }
        
        this.prompts = prompts;
    }
    
    public List<String> getPersonaNames()
    {
        return new ArrayList<String>(this.prompts.stream()
        		.map(el -> el.name)
        		.collect(Collectors.toList()));
    }
    
    public String getPrompt(String persona)
    {
    	Prompt prompt = this.prompts.stream().filter(el -> persona.toLowerCase().equals(el.name.toLowerCase())).findAny().orElse(null);
        return prompt != null ? prompt.prompt : "";
    }
}
