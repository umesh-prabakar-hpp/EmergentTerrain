package com.emergentterrain.research.publication;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.PreRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComputationalPublicationPhaseTest {

    @Test
    void cleanTraceWithoutAPlanRemainsComputationalNote() {
        PublicationAssessment assessment = ComputationalPublicationPhase.assess(
                new PreRegistration("h", "p", "f", ""),
                List.of(new ToolInvocation("verify_constant", "{}", "{}", 0)),
                "Related work");

        assertThat(assessment.label()).isEqualTo(PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE);
        assertThat(assessment.missingRequirements())
                .contains("separate discovery and confirmation scopes plus a fixed selection rule");
    }

    @Test
    void completeComputationalContractIsArtifactBackedCandidate() {
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "integer windows [1e6, 1e7)",
                "integer windows [1e8, 1e9)",
                "choose lowest RMSE; ties choose simpler formula",
                "repeat at three window widths", "./scripts/reproduce-prime-gap.sh");
        PublicationAssessment assessment = ComputationalPublicationPhase.assess(
                new PreRegistration("h", "p", "f", "", plan),
                List.of(
                        new ToolInvocation("search_literature", "{}", "{}", 0),
                        new ToolInvocation("read_reference", "{}", "{}", 1),
                        new ToolInvocation("verify_constant", "{}", "{}", 2)),
                "## Related Work\n\nPositioned against the checked primary source.");

        assertThat(assessment.label())
                .isEqualTo(PublicationLabel.ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE);
        assertThat(assessment.missingRequirements()).isEmpty();
    }

    @Test
    void identicalScopesDoNotCountAsSelectionSeparation() {
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "windows near 1e8", "windows near 1e8", "lowest error", "change width", "./run.sh");

        assertThat(plan.hasSelectionSeparation()).isFalse();
    }
}
