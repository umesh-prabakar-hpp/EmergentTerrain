package com.emergentterrain.web;

import java.util.List;

public class QuestStateRequest {
    private String token;
    private String season;
    private List<QuestItem> quests;
    private String lastObserverPrompt;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getSeason() {
        return season;
    }

    public void setSeason(String season) {
        this.season = season;
    }

    public List<QuestItem> getQuests() {
        return quests;
    }

    public void setQuests(List<QuestItem> quests) {
        this.quests = quests;
    }

    public String getLastObserverPrompt() {
        return lastObserverPrompt;
    }

    public void setLastObserverPrompt(String lastObserverPrompt) {
        this.lastObserverPrompt = lastObserverPrompt;
    }
}
