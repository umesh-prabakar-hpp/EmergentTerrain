package com.emergentterrain.research.literature;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Spring wiring for the Phase 34 local SQLite adapter. Disabled by default. */
@Configuration
@ConditionalOnProperty(name = "app.research.literature.phase34-sqlite.enabled", havingValue = "true")
public class Phase34SqliteLiteratureConfig {

    @Bean
    public Phase34SqliteLiterature phase34SqliteLiterature(
            @Value("${app.research.literature.phase34-sqlite.index-dir:${user.home}/.emergentterrain/indexes/math-literature}")
            String indexDir) {
        return new Phase34SqliteLiterature(Path.of(indexDir));
    }
}
