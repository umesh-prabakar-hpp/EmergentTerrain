package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchParameterOverride;
import com.emergentterrain.researchcontract.ResearchExecutionPreparation;
import com.emergentterrain.researchcontract.ResearchFormulationSpecification;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FrozenResearchExecutionPreparerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void blocksStandardCatalogComputationUntilItsInputsAreFrozen() {
        ResearchExecutionPreparationRepository repository = mock(ResearchExecutionPreparationRepository.class);
        FrozenResearchExecutionPreparer preparer = new FrozenResearchExecutionPreparer(repository, mapper);
        ResearchDraftPlan draft = draft(ResearchDraftExecutionIntent.STANDARD_CATALOG, mapper.createObjectNode());

        ResearchExecutionPreparation preparation = preparer.prepare(draft,
                FrozenResearchBranchPreparer.PreparedBranch.notApplicable());

        assertThat(preparation.preparationVersion())
                .isEqualTo(ResearchExecutionPreparation.FROZEN_EXECUTION_PREPARATION_V1);
        assertThat(preparation.readiness()).isEqualTo(ResearchExecutionPreparation.AWAITING_COMPUTATION_INPUTS);
        assertThat(preparation.canonicalInput().path("topic").asText()).isEqualTo(draft.topic());
        assertThat(preparation.canonicalInput().path("executionPayload").isObject()).isTrue();
        verify(repository).save(org.mockito.ArgumentMatchers.any(ResearchExecutionPreparationRecord.class));
    }

    @Test
    void retainsCanonicalBranchInputButDoesNotAuthorizeComputation() {
        ResearchExecutionPreparationRepository repository = mock(ResearchExecutionPreparationRepository.class);
        FrozenResearchExecutionPreparer preparer = new FrozenResearchExecutionPreparer(repository, mapper);
        ResearchParameterOverride override = new ResearchParameterOverride(ResearchParameterOverride.LEGACY_FOLLOW_UP_V1,
                42, 1.5, 0.6, "prior scan");
        ResearchDraftPlan draft = draft(ResearchDraftExecutionIntent.DEFERRED_FOLLOW_UP_STUDY,
                mapper.valueToTree(override));

        ResearchExecutionPreparation preparation = preparer.prepare(draft,
                new FrozenResearchBranchPreparer.PreparedBranch("PREPARED", mapper.valueToTree(override)));

        assertThat(preparation.readiness()).isEqualTo(ResearchExecutionPreparation.PREPARED_NO_COMPUTATION);
        assertThat(preparation.canonicalInput().path("executionPayload").path("parameterOne").asInt()).isEqualTo(42);
    }

    @Test
    void recordsFrozenFormulationButStillDoesNotAuthorizeComputation() {
        ResearchExecutionPreparationRepository repository = mock(ResearchExecutionPreparationRepository.class);
        FrozenResearchExecutionPreparer preparer = new FrozenResearchExecutionPreparer(repository, mapper);
        UUID subjectId = UUID.randomUUID();
        ResearchFormulationSpecification formulation = new ResearchFormulationSpecification(
                ResearchFormulationSpecification.LEGACY_FORMULATION_V1, subjectId, "MATHEMATICS", "topic",
                42, 1.5, "Does the scan support the relation?", "The relation holds.", "A margin appears.",
                "A counterexample falsifies it.", "");
        ResearchDraftPlan draft = new ResearchDraftPlan(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), subjectId,
                "MATHEMATICS", "topic", "CANDIDATE", "legacy-standard-catalog-v1", "draft-fingerprint",
                ResearchDraftExecutionIntent.STANDARD_CATALOG, mapper.createObjectNode(), null, formulation,
                Instant.parse("2026-08-10T00:00:00Z"));

        ResearchExecutionPreparation preparation = preparer.prepare(draft,
                FrozenResearchBranchPreparer.PreparedBranch.notApplicable());

        assertThat(preparation.readiness())
                .isEqualTo(ResearchExecutionPreparation.FORMULATION_FROZEN_AWAITING_COMPUTATION);
        assertThat(preparation.canonicalInput().path("frozenFormulation").path("parameterOne").asInt()).isEqualTo(42);
    }

    private static ResearchDraftPlan draft(String executionKind, com.fasterxml.jackson.databind.JsonNode payload) {
        UUID draftId = UUID.randomUUID();
        return new ResearchDraftPlan(draftId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "MATHEMATICS", "topic", "CANDIDATE", "legacy-standard-catalog-v1", "draft-fingerprint",
                executionKind, payload, Instant.parse("2026-08-10T00:00:00Z"));
    }
}
