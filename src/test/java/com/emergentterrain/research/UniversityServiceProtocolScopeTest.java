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
}
