package com.emergentterrain.research.question;

import com.emergentterrain.research.PreRegistration;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionServedProtocolCloneGuardTest {

    @Test
    void identicalQuestionServedProtocolIsDeclinedBeforeNarration() {
        PreRegistration source = registration("locked-constant-limit:v1;lhs=mean_divisor_excess;"
                + "target=1.15443;scale_ladder=true;");
        PaperOpenQuestion question = new PaperOpenQuestion("P-source", "mean_divisor_excess",
                "Does the observed constant persist at larger scales?", "SCOPE",
                QuestionServedProtocolCloneGuard.fingerprint(source));

        assertThat(QuestionServedProtocolCloneGuard.isClone(question, "mean_divisor_excess",
                registration(" locked-constant-limit:v1; lhs=mean_divisor_excess; "
                        + "target=1.15443; scale_ladder=true; "))).isTrue();
    }

    @Test
    void changedProtocolOrDifferentFamilyRemainsEligible() {
        PreRegistration source = registration("locked-max-divisor-count-measurement:v1;"
                + "lhs=max_divisor_count;rhs=wigert_bound;fit=fit,1000000000,5000,20;");
        PaperOpenQuestion question = new PaperOpenQuestion("P-source", "max_divisor_count",
                "Does the fit survive a wider holdout?", "SENSITIVITY",
                QuestionServedProtocolCloneGuard.fingerprint(source));

        PreRegistration changedHoldout = registration("locked-max-divisor-count-measurement:v1;"
                + "lhs=max_divisor_count;rhs=wigert_bound;fit=fit,1000000000,10000,10;");

        assertThat(QuestionServedProtocolCloneGuard.isClone(question, "max_divisor_count", changedHoldout))
                .isFalse();
        assertThat(QuestionServedProtocolCloneGuard.isClone(question, "mean_divisor_excess", source))
                .isFalse();
    }

    @Test
    void legacyQuestionWithoutFingerprintDoesNotBlockAnAllocation() {
        PaperOpenQuestion legacy = new PaperOpenQuestion("P-legacy", "mean_divisor_excess",
                "Does the trend persist?", "SCOPE");

        assertThat(QuestionServedProtocolCloneGuard.isClone(legacy, "mean_divisor_excess",
                registration("locked-constant-limit:v1;lhs=mean_divisor_excess;"
                        + "target=1.15443;scale_ladder=true;"))).isFalse();
    }

    private static PreRegistration registration(String mappingNotes) {
        return new PreRegistration("h", "p", "f", mappingNotes);
    }
}
