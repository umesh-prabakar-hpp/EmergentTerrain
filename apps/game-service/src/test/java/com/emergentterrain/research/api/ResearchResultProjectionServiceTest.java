package com.emergentterrain.research.api;

import com.emergentterrain.repo.ResearchResultProjectionRepository;
import com.emergentterrain.researchcontract.ResearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchResultProjectionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void storesOnlyAnApprovedDisplayOnlyPlanningResult() {
        ResearchResultProjectionRepository repository = mock(ResearchResultProjectionRepository.class);
        ResearchResultProjectionService service = new ResearchResultProjectionService(repository, mapper);
        ResearchResult result = result("DRAFT_CANDIDATE_PLANNED", mapper.createObjectNode()
                .put("draftId", "frozen-draft"));
        when(repository.existsById(result.resultId())).thenReturn(false);

        service.apply(result);

        ArgumentCaptor<com.emergentterrain.domain.ResearchResultProjection> saved = ArgumentCaptor.forClass(
                com.emergentterrain.domain.ResearchResultProjection.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOutcome()).isEqualTo("DRAFT_CANDIDATE_PLANNED");
    }

    @Test
    void storesCompositionEligibilityOnlyAsDisplaySafePlanningProvenance() {
        ResearchResultProjectionRepository repository = mock(ResearchResultProjectionRepository.class);
        ResearchResultProjectionService service = new ResearchResultProjectionService(repository, mapper);
        ResearchResult result = result("DRAFT_CANDIDATE_PLANNED", mapper.createObjectNode()
                .putObject("draftCompositionEligibility").put("status", "ELIGIBLE"));
        when(repository.existsById(result.resultId())).thenReturn(false);

        service.apply(result);

        verify(repository).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAnEffectRecommendationBeforeItCanBeProjected() {
        ResearchResultProjectionRepository repository = mock(ResearchResultProjectionRepository.class);
        ResearchResultProjectionService service = new ResearchResultProjectionService(repository, mapper);
        ResearchResult result = result("DRAFT_CANDIDATE_PLANNED", mapper.createObjectNode()
                .putObject("nested").put("effectRecommendation", "mutate-world"));

        assertThatThrownBy(() -> service.apply(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may not carry gameplay effects");

        verify(repository, never()).existsById(result.resultId());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsEffectFieldsRegardlessOfCommonJsonNamingStyle() {
        ResearchResultProjectionRepository repository = mock(ResearchResultProjectionRepository.class);
        ResearchResultProjectionService service = new ResearchResultProjectionService(repository, mapper);
        ResearchResult result = result("DRAFT_CANDIDATE_PLANNED", mapper.createObjectNode()
                .putObject("nested").put("gameplay_action", "mutate-world"));

        assertThatThrownBy(() -> service.apply(result))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may not carry gameplay effects");

        verify(repository, never()).existsById(result.resultId());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsAnOutcomeThatHasNotBeenApprovedForDisplayProjection() {
        ResearchResultProjectionRepository repository = mock(ResearchResultProjectionRepository.class);
        ResearchResultProjectionService service = new ResearchResultProjectionService(repository, mapper);

        assertThatThrownBy(() -> service.apply(result("EFFECT_RECOMMENDED", mapper.createObjectNode())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not approved");

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static ResearchResult result(String outcome, com.fasterxml.jackson.databind.JsonNode findings) {
        return new ResearchResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), outcome,
                "A display-only research planning record.", findings, Instant.parse("2026-08-10T12:00:00Z"));
    }
}
