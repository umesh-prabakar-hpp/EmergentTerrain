package com.emergentterrain.research.conjecture;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 30 — verifies the relaxed binding that the {@code @ConfigurationProperties} refactor
 * depends on: the production YAML keys are camelCase ({@code app.research.conjectureMiner.*})
 * while the bean prefix is canonical kebab-case ({@code app.research.conjecture-miner}). If this
 * mapping silently broke, every flipped production flag (triviality/rotation/composition all ON)
 * would revert to the OFF code defaults — so pin it. Uses the same {@link Binder} relaxed-binding
 * path the Spring container uses, with a camelCase property source (no full context).
 */
class ConjectureMinerPropertiesBindingTest {

    @Test
    void camelCaseYamlKeysBindToKebabPrefixedBean() {
        Map<String, Object> yaml = new HashMap<>();
        // Mirror the production application.yml key style exactly (camelCase under conjectureMiner).
        yaml.put("app.research.conjectureMiner.trivialityFilterEnabled", "true");
        yaml.put("app.research.conjectureMiner.investigationRotationEnabled", "true");
        yaml.put("app.research.conjectureMiner.compositionPersistCap", "7");
        yaml.put("app.research.conjectureMiner.stochasticNullEnabled", "true");
        yaml.put("app.research.conjectureMiner.stochasticNullAlpha", "0.1");
        yaml.put("app.research.conjectureMiner.multipleComparisonsMethod", "bonferroni");
        yaml.put("app.research.conjectureMiner.privateValidatorEnabled", "true");

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", yaml));

        ConjectureMinerProperties p = Binder.get(env)
                .bind("app.research.conjecture-miner", ConjectureMinerProperties.class)
                .get();

        assertThat(p.isTrivialityFilterEnabled()).isTrue();
        assertThat(p.isInvestigationRotationEnabled()).isTrue();
        assertThat(p.getCompositionPersistCap()).isEqualTo(7);
        assertThat(p.isStochasticNullEnabled()).isTrue();
        assertThat(p.getStochasticNullAlpha()).isEqualTo(0.1);
        assertThat(p.getMultipleComparisonsMethod()).isEqualTo("bonferroni");
        assertThat(p.isPrivateValidatorEnabled()).isTrue();
        // Unset key keeps its code default.
        assertThat(p.isDimensionFilterEnabled()).isFalse();
    }
}
