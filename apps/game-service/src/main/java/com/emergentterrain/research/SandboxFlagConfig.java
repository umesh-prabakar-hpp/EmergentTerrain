package com.emergentterrain.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Phase 19 — wires {@code app.research.sandbox.*} from {@code application.yml}
 * into the sandbox's process-wide static flags at startup:
 * {@code relaxed-grammar.enabled} → {@link SandboxStaticChecker}, and
 * {@code max-array-len} → {@link SandboxEngine}'s array element cap.
 *
 * <p>The static checker is a stateless utility (no Spring lifecycle of its
 * own); this bean exists solely so operators can flip the rollback flag via
 * configuration without rebuilding the binary. The default in YAML and the
 * Java-side default both stay {@code true} — flag-off is the rollback path
 * for the rewrites, not the day-to-day behaviour.
 *
 * <p>See {@code docs/phases/phase-19-sandbox-friction.md}
 * Open Question 2 (process-wide vs per-room) and the Rollback section.
 */
@Component
public class SandboxFlagConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxFlagConfig.class);

    private final boolean relaxedGrammarEnabled;
    private final int maxArrayLen;

    public SandboxFlagConfig(
            @Value("${app.research.sandbox.relaxed-grammar.enabled:true}")
            boolean relaxedGrammarEnabled,
            @Value("${app.research.sandbox.max-array-len:16384}")
            int maxArrayLen) {
        this.relaxedGrammarEnabled = relaxedGrammarEnabled;
        this.maxArrayLen = maxArrayLen;
    }

    @PostConstruct
    void apply() {
        SandboxStaticChecker.setRelaxedGrammarEnabled(relaxedGrammarEnabled);
        SandboxEngine.setMaxArrayLen(maxArrayLen);
        log.info("SandboxFlagConfig: relaxed-grammar.enabled={} max-array-len={} (~{} kB/array eager)",
                relaxedGrammarEnabled, SandboxEngine.maxArrayLen(), SandboxEngine.maxArrayLen() * 8 / 1024);
    }
}
