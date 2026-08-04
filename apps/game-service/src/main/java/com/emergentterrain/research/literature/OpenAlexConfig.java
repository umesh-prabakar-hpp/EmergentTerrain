package com.emergentterrain.research.literature;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Phase 32 §32.5 step 1 — Spring wiring for the OpenAlex client (phase-32-openalex-corpus.md
 * item A). Dormant by default: nothing constructs unless
 * {@code app.research.literature.openalex.enabled=true}. The timeout-recovery worker may use it
 * asynchronously to discover open-access backfill candidates; that worker never treats an
 * OpenAlex response as evidence. The offline corpus builder ({@link NtCorpusBuilder}) constructs
 * its own client from env vars and does not need Spring.
 *
 * <p>The API key arrives via the secrets-file convention ({@code ${OPENALEX_API_KEY:}} — resolved
 * by ExternalSecretsEnvironmentPostProcessor from {@code ~/.emergentterrain/secrets.enc}; blank =
 * anonymous tier, which works with a ~$0.10/day allowance). Never a literal in YAML.
 */
@Configuration
@ConditionalOnProperty(name = "app.research.literature.openalex.enabled", havingValue = "true")
public class OpenAlexConfig {

    @Bean
    public OpenAlexCreditLedger openAlexCreditLedger(
            @Value("${app.research.literature.openalex.state-dir:${user.home}/.emergentterrain/corpus-nt}")
            String stateDir,
            @Value("${app.research.literature.openalex.daily-credit-ceiling:0.50}")
            double dailyCeilingUsd) {
        return new OpenAlexCreditLedger(Path.of(stateDir, "openalex-ledger.json"), dailyCeilingUsd);
    }

    @Bean
    public OpenAlexClient openAlexClient(
            OpenAlexCreditLedger ledger,
            @Value("${app.research.literature.openalex.base-url:}") String baseUrl,
            @Value("${app.research.literature.openalex.api-key:}") String apiKey,
            @Value("${app.research.literature.openalex.mailto:}") String mailto,
            @Value("${app.research.literature.openalex.state-dir:${user.home}/.emergentterrain/corpus-nt}")
            String stateDir) {
        return new OpenAlexClient(baseUrl, apiKey, mailto, ledger,
                new OpenAlexResponseCache(Path.of(stateDir, "openalex-cache.jsonl")));
    }
}
