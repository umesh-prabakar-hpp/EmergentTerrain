package com.emergentterrain.service;

import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.NarrationResult;
import com.emergentterrain.research.RelationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 Iter 9 — parser tests for the optional formal_findings JSON shape.
 */
class FormalFindingParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesValidFormalFinding() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "formal_findings":[
                    {"relation_type":"PROPORTIONAL",
                     "dependent_variable":"civic_trust",
                     "independent_variables":["highway_density","education_budget"],
                     "equation_form":"trust ~ density + edu",
                     "claim_status":"SUPPORTED",
                     "confidence":0.8,
                     "valid_region_min":0.2,
                     "valid_region_max":0.8}
                 ]}
                """;

        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.formalFindings()).hasSize(1);
        NarrationResult.ExtractedFinding ef = r.formalFindings().get(0);
        assertThat(ef.relationType()).isEqualTo(RelationType.PROPORTIONAL);
        assertThat(ef.dependentVariable()).isEqualTo("civic_trust");
        assertThat(ef.independentVariables()).containsExactly("highway_density", "education_budget");
        assertThat(ef.equationForm()).isEqualTo("trust ~ density + edu");
        assertThat(ef.claimStatus()).isEqualTo(MathPhysicsEngine.ClaimStatus.SUPPORTED);
        assertThat(ef.confidence()).isEqualTo(0.8);
        assertThat(ef.validRegionMin()).isEqualTo(0.2);
        assertThat(ef.validRegionMax()).isEqualTo(0.8);
    }

    @Test
    void unknownRelationTypeFallsBackToNull() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "formal_findings":[
                    {"relation_type":"BANANA","dependent_variable":"x",
                     "independent_variables":["y"],"claim_status":"SUPPORTED","confidence":0.5}
                 ]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.formalFindings()).hasSize(1);
        assertThat(r.formalFindings().get(0).relationType()).isEqualTo(RelationType.NULL);
    }

    @Test
    void unknownClaimStatusFallsBackToObservational() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "formal_findings":[
                    {"relation_type":"PROPORTIONAL","dependent_variable":"x",
                     "independent_variables":["y"],"claim_status":"BANANA","confidence":0.5}
                 ]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.formalFindings().get(0).claimStatus())
                .isEqualTo(MathPhysicsEngine.ClaimStatus.OBSERVATIONAL);
    }

    @Test
    void missingDependentVariableSkipsEntry() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "formal_findings":[
                    {"relation_type":"PROPORTIONAL","independent_variables":["y"],"confidence":0.5},
                    {"relation_type":"PROPORTIONAL","dependent_variable":"x",
                     "independent_variables":["y"],"confidence":0.5}
                 ]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        // First entry has no dependent_variable; second is valid.
        assertThat(r.formalFindings()).hasSize(1);
        assertThat(r.formalFindings().get(0).dependentVariable()).isEqualTo("x");
    }

    @Test
    void confidenceClampedToValidRange() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "formal_findings":[
                    {"relation_type":"PROPORTIONAL","dependent_variable":"x",
                     "independent_variables":["y"],"confidence":1.5},
                    {"relation_type":"PROPORTIONAL","dependent_variable":"x",
                     "independent_variables":["y"],"confidence":-0.5}
                 ]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.formalFindings().get(0).confidence()).isEqualTo(1.0);
        assertThat(r.formalFindings().get(1).confidence()).isEqualTo(0.0);
    }

    @Test
    void legacyResponseHasEmptyFormalFindings() {
        String json = """
                {"hypothesis":"h","findings":"f","conclusion":"c","confidenceScore":0.7,
                 "cited_paper_ids":["P-aaaa-1"]}
                """;
        NarrationResult r = ResearchNarrationService.parseNarrationResult(MAPPER, json);

        assertThat(r.formalFindings()).isEmpty();
    }
}
