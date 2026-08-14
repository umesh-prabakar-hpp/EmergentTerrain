package com.emergentterrain.researchservice;

import com.emergentterrain.research.UniversityService;
import com.emergentterrain.research.conjecture.ConjectureMinerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the controlled shadow-only process cannot activate legacy execution schedulers. */
class ShadowOnlyLegacyExecutionConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LegacyExecutionCandidates.class)
            // Deliberately enable both normal schedulers: shadow-only must still suppress them.
            .withPropertyValues(
                    "app.university.enabled=true",
                    "app.research.conjectureMiner.enabled=true",
                    "app.research-service.lifecycle-shadow.shadow-only=true");

    @Test
    void shadowOnlySuppressesLegacyUniversityAndConjectureMinerEvenWhenNormallyEnabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(UniversityService.class);
            assertThat(context).doesNotHaveBean(ConjectureMinerService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(useDefaultFilters = false, includeFilters = {
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UniversityService.class),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ConjectureMinerService.class)
    })
    static class LegacyExecutionCandidates {
    }
}
