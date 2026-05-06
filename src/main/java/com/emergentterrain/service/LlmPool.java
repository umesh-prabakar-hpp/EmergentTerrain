package com.emergentterrain.service;

import java.util.List;
import java.util.function.Consumer;

public interface LlmPool {
    String callSync(String prompt);
    void callStream(String prompt, Consumer<String> tokenConsumer);
    String selectedModel();
    default List<String> selectedModels() { return List.of(selectedModel()); }
    int backendCount();
    int healthyCount();
}
