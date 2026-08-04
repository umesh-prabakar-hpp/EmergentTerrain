package com.emergentterrain.web;

public class RoomBotPromptResponse {
    private String type;
    private String prompt;
    private String lastPrompt;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getLastPrompt() {
        return lastPrompt;
    }

    public void setLastPrompt(String lastPrompt) {
        this.lastPrompt = lastPrompt;
    }
}
