package com.emergentterrain.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Phase 19 — wires {@code app.research.sandbox.relaxed-grammar.enabled} from
 * {@code application.yml} into {@link SandboxStaticChecker}'s process-wide
 * relaxed-grammar flag at startup.
 *
 * <p>The static checker is a stateless utility (no Spring lifecycle of its
 * own); this bean exists solely so operators can flip the rollback flag via
 * configuration without rebuilding the binary. The default in YAML and the
 * Java-side default both stay {@code true} — flag-off is the rollback path
 * for the rewrites, not the day-to-day behaviour.
 *
 * <p>See {@code phases-for-human-like-performance/phase-19-sandbox-friction.md}
 * Open Question 2 (process-wide vs per-room) and the Rollback section.
 */
@Component
public class SandboxFlagConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxFlagConfig.class);

    private final boolean relaxedGrammarEnabled;

    public SandboxFlagConfig(
            @Value("${app.research.sandbox.relaxed-grammar.enabled:true}")
            boolean relaxedGrammarEnabled) {
        this.relaxedGrammarEnabled = relaxedGrammarEnabled;
    }

    @PostConstruct
    void apply() {
        SandboxStaticChecker.setRelaxedGrammarEnabled(relaxedGrammarEnabled);
        log.info("SandboxFlagConfig: relaxed-grammar.enabled={}", relaxedGrammarEnabled);
    }
}
