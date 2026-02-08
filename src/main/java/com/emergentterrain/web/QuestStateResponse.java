package com.emergentterrain.web;

import java.util.List;

public class QuestStateResponse {
    private String season;
    private List<QuestItem> quests;
    private String lastObserverPrompt;

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
