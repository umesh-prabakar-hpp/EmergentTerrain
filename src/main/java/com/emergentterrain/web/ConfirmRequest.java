package com.emergentterrain.web;

public class ConfirmRequest {
    private String token;
    private GameDelta delta;
    private boolean confirmed;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public GameDelta getDelta() {
        return delta;
    }

    public void setDelta(GameDelta delta) {
        this.delta = delta;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }
}
