package com.emergentterrain.research.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the game-to-research v1 API boundary. */
@ConfigurationProperties(prefix = "app.research-api")
public record ResearchApiProperties(String mode, String baseUrl) {

    public ResearchApiProperties {
        mode = mode == null || mode.isBlank() ? "local" : mode;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://127.0.0.1:8082" : baseUrl;
    }
}
