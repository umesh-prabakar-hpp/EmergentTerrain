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
                "Related work", "PUBLISHED");

        assertThat(assessment.label()).isEqualTo(PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE);
        assertThat(assessment.missingRequirements())
                .contains("separate discovery and confirmation scopes plus a fixed selection rule");
    }

    @Test
    void toolNamesWithoutSourceEvidenceDoNotQualify() {
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
                "## Related Work\n\nPositioned against the checked primary source.", "PUBLISHED");

        assertThat(assessment.label()).isEqualTo(PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE);
        assertThat(assessment.missingRequirements())
                .contains("source-grounded related-work positioning");
    }

    @Test
    void identicalScopesDoNotCountAsSelectionSeparation() {
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "windows near 1e8", "windows near 1e8", "lowest error", "change width", "./run.sh");

        assertThat(plan.hasSelectionSeparation()).isFalse();
    }

    @Test
    void acceptedReadWhosePassageContainsBothSearchAnchorsQualifies() {
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "integer windows [1e6, 1e7)", "integer windows [1e8, 1e9)",
                "choose lowest RMSE", "repeat at three widths", "./reproduce.sh");
        PublicationAssessment assessment = ComputationalPublicationPhase.assess(
                new PreRegistration("h", "p", "f", "", plan),
                List.of(
                        new ToolInvocation("search_literature",
                                "{\"subject_anchors\":[\"dirichlet\",\"divisor\"]}",
                                "search_literature (grounded, corpus of 10 works): EVIDENCE\n- [W123] Prior work",
                                0),
                        new ToolInvocation("read_reference", "{}",
                                "read_reference W123 — Prior work [source: ar5iv HTML]\n"
                                        + "Top passages matching \"claim\":\n---\n"
                                        + "The Dirichlet divisor problem has this error term.",
                                1),
                        new ToolInvocation("assess_reference_relevance", "{}",
                                "reference_relevance_assessment W123 — ACCEPTED RELEVANT: matched passage.",
                                2)),
                "## Related Work\n\nWe extend the tested range in [W123].", "PUBLISHED");

        assertThat(assessment.label())
                .isEqualTo(PublicationLabel.ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE);
        assertThat(assessment.missingRequirements()).isEmpty();
    }

    @Test
    void citedReadWithoutAcceptedSemanticAssessmentDoesNotQualify() {
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "discovery", "confirmation", "fixed rule", "sensitivity", "./reproduce.sh");
        PublicationAssessment assessment = ComputationalPublicationPhase.assess(
                new PreRegistration("h", "p", "f", "", plan), List.of(
                        new ToolInvocation("search_literature",
                                "{\"subject_anchors\":[\"dirichlet\",\"divisor\"]}",
                                "search_literature: EVIDENCE [W123]", 0),
                        new ToolInvocation("read_reference", "{}",
                                "read_reference W123 — source\nTop passages matching \"claim\":\n---\n"
                                        + "The Dirichlet divisor problem.", 1)),
                "Related Work [W123].", "PUBLISHED");

        assertThat(assessment.missingRequirements()).contains("source-grounded related-work positioning");
    }

    @Test
    void establishedConstantTargetNeedsRelevantAssessmentAndScalarBearingPassage() {
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "discovery", "confirmation", "fixed rule", "sensitivity", "./reproduce.sh");
        PreRegistration registration = new PreRegistration("h", "p", "f",
                "locked-constant-limit:v1;lhs=omega_Omega_gap;literature_required=true", plan);
        List<ToolInvocation> offTargetTrace = List.of(
                new ToolInvocation("search_literature", "{\"subject_anchors\":[\"omega\",\"gap\"]}",
                        "search_literature: EVIDENCE [HF77] Characters of prime power degree", 0),
                new ToolInvocation("read_reference", "{}",
                        "read_reference HF77 — Characters of prime power degree [source: core]\n"
                                + "Top passages matching \"omega gap\":\n---\n"
                                + "The character has prime power degree.", 1),
                new ToolInvocation("assess_reference_relevance", "{}",
                        "reference_relevance_assessment HF77 — ACCEPTED RELEVANT: metadata only.", 2));

        PublicationAssessment offTarget = ComputationalPublicationPhase.assess(
                registration, offTargetTrace, "Related Work cites [HF77].", "PUBLISHED");
        assertThat(offTarget.label()).isEqualTo(PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE);
        assertThat(offTarget.missingRequirements()).contains("source-grounded related-work positioning");

        List<ToolInvocation> groundedTrace = List.of(
                new ToolInvocation("search_literature", "{\"subject_anchors\":[\"omega\",\"gap\"]}",
                        "search_literature: EVIDENCE [HF78] Prime factor multiplicities", 0),
                new ToolInvocation("read_reference", "{}",
                        "read_reference HF78 — Prime factor multiplicities [source: core]\n"
                                + "Top passages matching \"omega gap\":\n---\n"
                                + "The difference Omega(n)-omega(n) records repeated prime factors.", 1),
                new ToolInvocation("assess_reference_relevance", "{}",
                        "reference_relevance_assessment HF78 — ACCEPTED RELEVANT: metadata only.", 2));
        PublicationAssessment grounded = ComputationalPublicationPhase.assess(
                registration, groundedTrace, "Related Work cites [HF78].", "PUBLISHED");
        assertThat(grounded.label()).isEqualTo(PublicationLabel.ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE);
    }

    @Test
    void successfullyReadSearchKeysKeepsOnlyIssuedAndReadKeysInTraceOrder() {
        List<String> keys = ComputationalPublicationPhase.successfullyReadSearchKeys(List.of(
                new ToolInvocation("search_literature", "{}",
                        "search_literature: EVIDENCE [HF20A94C6E8F45DBCAF99E] [HF0B11C2D3E4F5A6B7C8D9E]", 0),
                new ToolInvocation("read_reference", "{}",
                        "read_reference HF0B11C2D3E4F5A6B7C8D9E — source\nTop passages matching \"claim\":\npassage", 1),
                new ToolInvocation("read_reference", "{}",
                        "read_reference HF1A2B3C4D5E6F7A8B9C0D1 — source\nTop passages matching \"claim\":\npassage", 2),
                new ToolInvocation("read_reference", "{}",
                        "read_reference HF20A94C6E8F45DBCAF99E — source\nTop passages matching \"claim\":\npassage", 3)));

        assertThat(keys).containsExactly("HF0B11C2D3E4F5A6B7C8D9E", "HF20A94C6E8F45DBCAF99E");
    }

    @Test
    void nonPublishedWorkflowCannotBecomeArtifactBackedCandidate() {
        ComputationalStudyPlan plan = new ComputationalStudyPlan(
                "discovery", "confirmation", "fixed rule", "sensitivity", "./reproduce.sh");
        PublicationAssessment assessment = ComputationalPublicationPhase.assess(
                new PreRegistration("h", "p", "f", "", plan),
                List.of(
                        new ToolInvocation("search_literature", "{}", "search_literature: [W123] Prior", 0),
                        new ToolInvocation("read_reference", "{}",
                                "read_reference W123 [source: core]\nTop passages matching \"claim\":\nPassage",
                                1)),
                "Positioned against [W123].", "NEEDS_REVISION");

        assertThat(assessment.label()).isEqualTo(PublicationLabel.COMPUTATIONAL_RESEARCH_NOTE);
        assertThat(assessment.missingRequirements()).contains("clean PUBLISHED workflow status");
    }
}
