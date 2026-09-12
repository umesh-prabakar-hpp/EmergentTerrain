package com.emergentterrain.research.quality;

import com.emergentterrain.repo.ResearchPaperQualityAssessmentRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Guards the production constructor when the deterministic-clock test constructor is present. */
class ResearchQualityAssessmentServiceWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ResearchPaperRepository.class, () -> mock(ResearchPaperRepository.class))
            .withBean(ResearchPaperQualityAssessmentRepository.class,
                    () -> mock(ResearchPaperQualityAssessmentRepository.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ResearchQualityAssessmentService.class);

    @Test
    void createsTheServiceUsingItsRepositoryAndMapperDependencies() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(ResearchQualityAssessmentService.class));
    }
}
