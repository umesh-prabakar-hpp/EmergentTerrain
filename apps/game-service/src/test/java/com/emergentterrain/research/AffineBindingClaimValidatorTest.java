package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AffineBindingClaimValidatorTest {
    private static ToolInvocation verify(String args, String result) {
        return new ToolInvocation("verify_conjecture", args, result, 0);
    }

    @Test
    void bindingClaimNeedsSameGridRefutation() {
        String held = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":0,"
                + "\"window_start\":1000000,\"window_size\":5000,\"num_windows\":10}";
        assertThat(AffineBindingClaimValidator.claimWithoutDocumentedPair("", "binding regime", "",
                List.of(verify(held, heldResult())))).isTrue();
    }

    @Test
    void documentedPairSupportsBindingClaim() {
        String held = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":0,"
                + "\"window_start\":1000000,\"window_size\":5000,\"num_windows\":10}";
        String refuted = "{\"lhs\":\"x\",\"rhs\":\"y\",\"k\":1,\"c\":-0.005,"
                + "\"window_start\":1000000,\"window_size\":5000,\"num_windows\":10}";
        assertThat(AffineBindingClaimValidator.claimWithoutDocumentedPair("", "binding regime", "",
                List.of(verify(held, heldResult()),
                        verify(refuted, "x REFUTED across 10 window(s).")))).isFalse();
    }

    @Test
    void bindingClaimWithoutParseableHeldEvidenceIsUncovered() {
        assertThat(AffineBindingClaimValidator.claimWithoutDocumentedPair("", "demonstrated binding", "",
                List.of(verify("{}", "unrelated tool output")))).isTrue();
    }

    private static String heldResult() {
        return "verify_conjecture: x <= +1.0 * y + +0.0 HELD across 10 window(s) of size 5000 "
                + "starting at 1000000. worst margin: -0.02000 at window starting 1000000. "
                + "relative slack: 0.0005";
    }
}
