package com.emergentterrain.research;

import com.emergentterrain.research.question.QuestionServedProtocolCloneGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProtocolReuseProvenanceTest {

    @Test
    void attachesLockedProvenanceWithoutChangingTheProtocolFields() {
        PreRegistration registration = new PreRegistration("h", "p", "f", "mapping");
        ProtocolReuseProvenance provenance = new ProtocolReuseProvenance(
                "P-1", "fingerprint-v1",
                ProtocolReuseProvenance.Reason.UNSETTLED_IDENTICAL_PROTOCOL, false);

        PreRegistration reused = registration.withProtocolReuseProvenance(provenance);

        assertThat(reused.hypothesis()).isEqualTo("h");
        assertThat(reused.mappingNotes()).isEqualTo("mapping");
        assertThat(reused.protocolReuseProvenance()).isEqualTo(provenance);
        assertThat(QuestionServedProtocolCloneGuard.fingerprint(reused))
                .isEqualTo(QuestionServedProtocolCloneGuard.fingerprint(registration));
    }

    @Test
    void rejectsUntraceableReuseProvenance() {
        assertThatThrownBy(() -> new ProtocolReuseProvenance("", "fingerprint-v1",
                ProtocolReuseProvenance.Reason.OPEN_QUESTION_CONTINUATION, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProtocolReuseProvenance("P-1", "",
                ProtocolReuseProvenance.Reason.OPEN_QUESTION_CONTINUATION, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void classifiesOnlyRetainedActionablePanelFeedbackAsRemediationRequested() {
        ResearchPaper source = mock(ResearchPaper.class);
        when(source.panelOutcome()).thenReturn("""
                {"aggregateGrades":{"honesty":0.8,"completeness":0.7,"rigor":0.6,"novelty":0.5},
                 "agreementScore":0.9,"consensusCitedCalls":[],"consensusOmittedRelevant":[],
                 "consensusContradictions":[],"recommendations":["add a holdout","cite the baseline"],
                 "reviews":[],"proposedEvidenceLabel":null}
                """);

        assertThat(ResearchCycleService.reuseReason(new ObjectMapper(), source))
                .isEqualTo(ProtocolReuseProvenance.Reason.REVIEWER_REMEDIATION_REQUESTED);
        assertThat(ResearchCycleService.reuseReason(null, source))
                .isEqualTo(ProtocolReuseProvenance.Reason.UNSETTLED_IDENTICAL_PROTOCOL);
    }
}
