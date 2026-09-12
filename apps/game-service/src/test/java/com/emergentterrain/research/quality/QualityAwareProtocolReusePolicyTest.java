package com.emergentterrain.research.quality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityAwareProtocolReusePolicyTest {

    @Test
    void notesDraftsAndUnassessedOutputsDoNotBlockReuse() {
        assertThat(QualityAwareProtocolReusePolicy.blocksOrdinaryReuse(List.of(
                ResearchQualityGrade.UNASSESSED,
                ResearchQualityGrade.RESEARCH_NOTE,
                ResearchQualityGrade.RESEARCH_DRAFT))).isFalse();
    }

    @Test
    void humanQualityOrBeyondResultBlocksOrdinaryReuse() {
        assertThat(QualityAwareProtocolReusePolicy.blocksOrdinaryReuse(List.of(
                ResearchQualityGrade.RESEARCH_NOTE,
                ResearchQualityGrade.HUMAN_QUALITY_PAPER))).isTrue();
        assertThat(QualityAwareProtocolReusePolicy.blocksOrdinaryReuse(List.of(
                ResearchQualityGrade.BEYOND_HUMAN_BENCHMARK))).isTrue();
    }
}
