package com.emergentterrain.web;

public class GameUpdateResponse {
    private String explanation;
    private boolean needsConfirmation;

    public GameUpdateResponse() {
    }

    public GameUpdateResponse(String explanation, boolean needsConfirmation) {
        this.explanation = explanation;
        this.needsConfirmation = needsConfirmation;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public boolean isNeedsConfirmation() {
        return needsConfirmation;
    }

    public void setNeedsConfirmation(boolean needsConfirmation) {
        this.needsConfirmation = needsConfirmation;
    }
}
