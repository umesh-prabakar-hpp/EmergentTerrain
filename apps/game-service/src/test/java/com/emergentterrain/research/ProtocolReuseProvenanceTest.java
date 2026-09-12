package com.emergentterrain.research;

import com.emergentterrain.research.question.QuestionServedProtocolCloneGuard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
