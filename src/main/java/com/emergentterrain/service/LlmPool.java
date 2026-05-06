package com.emergentterrain.service;

import java.util.function.Consumer;

public interface LlmPool {
    String callSync(String prompt);
    void callStream(String prompt, Consumer<String> tokenConsumer);
    String selectedModel();
    int backendCount();
    int healthyCount();
}
