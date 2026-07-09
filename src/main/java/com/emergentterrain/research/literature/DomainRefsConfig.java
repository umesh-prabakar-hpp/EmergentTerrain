package com.emergentterrain.research.literature;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 32 §32.5d — Spring wiring for the OEIS/DLMF domain-reference lookup tools. Dormant by
 * default ({@code app.research.literature.domain-refs.enabled=false}): no bean, so
 * {@code ResearchNarrationService}'s optional autowire stays null and the {@code lookup_oeis} /
 * {@code lookup_dlmf} tools are not advertised — byte-identical to pre-tool behavior. Flip on
 * once the py-sidecar is (re)started with {@code domain_refs.jl} and the corpus-nt/domain-refs
 * stores exist (built by scripts/build-oeis.py + build-dlmf.py).
 */
@Configuration
@ConditionalOnProperty(name = "app.research.literature.domain-refs.enabled", havingValue = "true")
public class DomainRefsConfig {

    @Bean
    public DomainRefClient domainRefClient(
            @Value("${app.julia.pySidecarUrl:http://127.0.0.1:8089}") String pySidecarUrl) {
        return new DomainRefClient(pySidecarUrl);
    }
}
