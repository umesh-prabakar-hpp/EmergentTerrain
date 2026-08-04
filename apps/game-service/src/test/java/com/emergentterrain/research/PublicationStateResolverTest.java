package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class PublicationStateResolverTest {

    @Test
    void supportedResultWithUncitedAcquiredSourceNeedsRevisionNotNull() {
        PublicationStateResolver.Resolution resolved = PublicationStateResolver.resolve(
                ResultClassification.SUPPORTED,
                PublicationStateResolver.WorkflowState.PUBLISHED,
                EnumSet.of(PublicationStateResolver.Deficit.CITATION));

        assertThat(resolved.semanticResult()).isEqualTo(ResultClassification.SUPPORTED);
        assertThat(resolved.paperStatus()).isEqualTo("NEEDS_REVISION");
        assertThat(resolved.reviewState()).isEqualTo(ReviewState.REVISABLE_DEFECT);
        assertThat(resolved.deficits()).containsExactly(PublicationStateResolver.Deficit.CITATION);
    }

    @Test
    void genuineNullRetainsSemanticStatusWhileDeficitsStayAuditable() {
        PublicationStateResolver.Resolution resolved = PublicationStateResolver.resolve(
                ResultClassification.NULL_RESULT,
                PublicationStateResolver.WorkflowState.PUBLISHED,
                EnumSet.allOf(PublicationStateResolver.Deficit.class));

        assertThat(resolved.semanticResult()).isEqualTo(ResultClassification.NULL_RESULT);
        assertThat(resolved.paperStatus()).isEqualTo("NULL_RESULT");
        assertThat(resolved.reviewState()).isEqualTo(ReviewState.REVISABLE_DEFECT);
        assertThat(resolved.deficits()).containsExactlyInAnyOrder(
                PublicationStateResolver.Deficit.CITATION,
                PublicationStateResolver.Deficit.SENSITIVITY,
                PublicationStateResolver.Deficit.REPLICATION);
    }

    @Test
    void supportedResultWithSensitivityAndReplicationDeficitsNeedsRevision() {
        PublicationStateResolver.Resolution resolved = PublicationStateResolver.resolve(
                ResultClassification.SUPPORTED,
                PublicationStateResolver.WorkflowState.PUBLISHED,
                EnumSet.of(PublicationStateResolver.Deficit.SENSITIVITY,
                        PublicationStateResolver.Deficit.REPLICATION));

        assertThat(resolved.paperStatus()).isEqualTo("NEEDS_REVISION");
        assertThat(resolved.deficits()).containsExactlyInAnyOrder(
                PublicationStateResolver.Deficit.SENSITIVITY,
                PublicationStateResolver.Deficit.REPLICATION);
    }

    @Test
    void structuralRejectionStillOutranksPublicationDeficits() {
        PublicationStateResolver.Resolution resolved = PublicationStateResolver.resolve(
                ResultClassification.SUPPORTED,
                PublicationStateResolver.WorkflowState.REJECTED,
                EnumSet.of(PublicationStateResolver.Deficit.CITATION));

        assertThat(resolved.paperStatus()).isEqualTo("REJECTED");
        assertThat(resolved.reviewState()).isEqualTo(ReviewState.GATE_FLAGGED);
    }
}
