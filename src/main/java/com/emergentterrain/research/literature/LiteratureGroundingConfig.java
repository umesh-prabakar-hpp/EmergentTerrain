package com.emergentterrain.research.literature;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 32 §32.5 step 4 — Spring wiring for the grounding caller. Dormant by default
 * ({@code app.research.literature.grounding.enabled=false}): no bean, so
 * {@code ResearchNarrationService}'s optional autowire stays null and {@code search_literature}
 * keeps today's curated-corpus behavior byte-identically. Flip after the 20-claim probe set
 * passes (the §32.5 2026-07-04 reframe's recommended trigger — operator decision).
 */
@Configuration
@ConditionalOnProperty(name = "app.research.literature.grounding.enabled", havingValue = "true")
public class LiteratureGroundingConfig {

    @Bean
    public GroundingClient groundingClient(
            @Value("${app.julia.pySidecarUrl:http://127.0.0.1:8089}") String pySidecarUrl) {
        return new GroundingClient(pySidecarUrl);
    }
}
