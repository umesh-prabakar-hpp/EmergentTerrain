package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UniversityServiceProtocolScopeTest {

    @Test
    void maxPrimeGapUsesItsSpecializedHoldoutProtocolInsteadOfGenericReplicationGate() {
        assertThat(UniversityService.usesSpecializedMaxPrimeGapProtocol("conjecture/max_prime_gap")).isTrue();
        assertThat(UniversityService.usesSpecializedMaxPrimeGapProtocol("conjecture/max_divisor_count")).isFalse();
        assertThat(UniversityService.usesSpecializedMaxPrimeGapProtocol(null)).isFalse();
    }

    @Test
    void unregisteredSecondOrderPivotIsNeverProtocolBackedEvidence() {
        assertThat(UniversityService.unregisteredSecondOrderPivot(null,
                "prof:gpt-5.6-terra:ok → second-order:engaged:mean_omega")).isTrue();
        assertThat(UniversityService.unregisteredSecondOrderPivot(new PreRegistration("h", "p", "f", "notes"),
                "second-order:engaged:mean_omega")).isFalse();
        assertThat(UniversityService.unregisteredSecondOrderPivot(null, "second-order:blocked:unregistered")).isFalse();
    }
}
