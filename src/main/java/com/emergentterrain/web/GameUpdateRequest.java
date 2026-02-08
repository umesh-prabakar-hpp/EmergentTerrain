package com.emergentterrain.web;

public class GameUpdateRequest {
    private String token;
    private GameDelta delta;

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
}
