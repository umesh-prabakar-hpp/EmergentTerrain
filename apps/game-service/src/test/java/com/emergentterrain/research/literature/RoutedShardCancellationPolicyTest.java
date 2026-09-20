package com.emergentterrain.research.literature;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutedShardCancellationPolicyTest {

    @Test
    void allowsCancellationOnlyAfterACompletedOrderedPrefixFillsAllReturnedSlots() {
        List<String> order = List.of("number-theory", "cross-list", "analysis");
        Map<String, String> completed = Map.of(
                "number-theory", "search_literature: EVIDENCE\n- [HF01] one\n- [HF02] two\n- [HF03] three");

        assertThat(RoutedShardCancellationPolicy.completedPrefixFillsAnswer(order, completed)).isTrue();
    }

    @Test
    void refusesCancellationWhenAnEarlierShardIsStillIncompleteOrKeysDoNotFillTheAnswer() {
        List<String> order = List.of("number-theory", "cross-list", "analysis");

        assertThat(RoutedShardCancellationPolicy.completedPrefixFillsAnswer(order,
                Map.of("cross-list", "- [HF01] one\n- [HF02] two\n- [HF03] three"))).isFalse();
        assertThat(RoutedShardCancellationPolicy.completedPrefixFillsAnswer(order,
                Map.of("number-theory", "- [HF01] one", "cross-list", "- [HF02] two"))).isFalse();
    }
}
