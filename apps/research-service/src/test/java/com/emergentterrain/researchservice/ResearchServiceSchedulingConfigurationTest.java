package com.emergentterrain.researchservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchServiceSchedulingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ResearchServiceSchedulingConfiguration.class);

    @Test
    void finiteMaintenanceModeRegistersNoSchedulingInfrastructure() {
        contextRunner.withPropertyValues("app.research-service.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("researchWorkerTaskScheduler");
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    void normalResearchServiceKeepsItsDedicatedScheduler() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("researchWorkerTaskScheduler");
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }
}
