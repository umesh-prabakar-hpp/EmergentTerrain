package com.emergentterrain.web;

public class AdviseRequest {
    private String token;
    private String streamId;
    private String question;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getStreamId() { return streamId; }
    public void setStreamId(String streamId) { this.streamId = streamId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
}
