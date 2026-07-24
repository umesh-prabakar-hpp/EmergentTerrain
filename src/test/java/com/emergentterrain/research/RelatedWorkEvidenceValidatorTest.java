package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelatedWorkEvidenceValidatorTest {

    @Test
    void flagsExternalTheoryAssertionAfterNoEvidenceSearch() {
        List<ToolInvocation> trace = List.of(new ToolInvocation(
                "search_literature", "{}",
                "search_literature: no sufficiently specific literature anchors were found; no evidence was issued.",
                0, "call_1"));

        assertThat(RelatedWorkEvidenceValidator.validate(
                "Established theory already implies this behavior.", trace))
                .containsExactly("related_work:external_claim_without_relevance_safe_evidence");
    }

    @Test
    void permitsHonestNoEvidenceRelatedWork() {
        List<ToolInvocation> trace = List.of(new ToolInvocation(
                "search_literature", "{}",
                "search_literature (Phase 34 SQLite): no theorem-index matches for the supplied query.",
                0, "call_1"));

        assertThat(RelatedWorkEvidenceValidator.validate(
                "No relevance-safe source for this claim was returned by the recorded search.", trace))
                .isEmpty();
    }

    @Test
    void permitsRelatedWorkWhenTraceContainsSafeEvidence() {
        List<ToolInvocation> trace = List.of(new ToolInvocation(
                "search_literature", "{}",
                "search_literature: EVIDENCE: [HF123] states the relevant theorem.",
                0, "call_1"));

        assertThat(RelatedWorkEvidenceValidator.validate(
                "Established theory is discussed in [HF123].", trace)).isEmpty();
    }

    @Test
    void ignoresPapersWithoutALiteratureLookup() {
        assertThat(RelatedWorkEvidenceValidator.validate(
                "Established theory is discussed elsewhere.", List.of())).isEmpty();
    }
}
