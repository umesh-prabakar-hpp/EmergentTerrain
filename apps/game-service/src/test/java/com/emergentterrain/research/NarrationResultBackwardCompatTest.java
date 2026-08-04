package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the backward-compatible NarrationResult constructors so older call sites keep
 * working as the record grows. Phase 7 G8 adds the 15th field {@code auditGapDimensions};
 * every legacy constructor must leave it at the safe default (null) and consumers must
 * treat null as "no audit gaps observed."
 */
class NarrationResultBackwardCompatTest {

    @Test
    void sevenArgCtorLeavesAuditGapDimensionsNull() {
        NarrationResult r = new NarrationResult("h", "f", "c", null, null, 0.5, List.of());
        assertThat(r.auditGapDimensions()).isNull();
    }

    @Test
    void eightArgCtorLeavesAuditGapDimensionsNull() {
        NarrationResult r = new NarrationResult("h", "f", "c", null, null, 0.5, List.of(), "path");
        assertThat(r.auditGapDimensions()).isNull();
    }

    @Test
    void nineArgCtorLeavesAuditGapDimensionsNull() {
        NarrationResult r = new NarrationResult("h", "f", "c", null, null, 0.5, List.of(),
                "path", List.of());
        assertThat(r.auditGapDimensions()).isNull();
    }

    @Test
    void tenArgCtorLeavesAuditGapDimensionsNull() {
        NarrationResult r = new NarrationResult("h", "f", "c", null, null, 0.5, List.of(),
                "path", List.of(), List.of());
        assertThat(r.auditGapDimensions()).isNull();
    }

    @Test
    void twelveArgCtorLeavesAuditGapDimensionsNull() {
        NarrationResult r = new NarrationResult("h", "f", "c", null, null, 0.5, List.of(),
                "path", List.of(), List.of(), "tid", "v1");
        assertThat(r.auditGapDimensions()).isNull();
    }

    @Test
    void fourteenArgCtorLeavesAuditGapDimensionsNull() {
        NarrationResult r = new NarrationResult("h", "f", "c", null, null, 0.5, List.of(),
                "path", List.of(), List.of(), "tid", "v1", "discussion", "relatedWork");
        assertThat(r.auditGapDimensions()).isNull();
    }

    @Test
    void fifteenArgCtorRoundTripsAuditGapDimensions() {
        List<String> gaps = List.of("physical_mechanism", "uncertainty");
        NarrationResult r = new NarrationResult("h", "f", "c", null, null, 0.5, List.of(),
                "path", List.of(), List.of(), "tid", "v1", "d", "rw", gaps);
        assertThat(r.auditGapDimensions()).containsExactly("physical_mechanism", "uncertainty");
    }

    @Test
    void withAuditGapDimensionsReturnsCopyWithDimensionsStamped() {
        NarrationResult original = new NarrationResult("h", "f", "c", null, null, 0.5, List.of());
        NarrationResult stamped = original.withAuditGapDimensions(List.of("physical_mechanism"));
        assertThat(original.auditGapDimensions()).isNull();
        assertThat(stamped.auditGapDimensions()).containsExactly("physical_mechanism");
        // Other fields preserved.
        assertThat(stamped.hypothesis()).isEqualTo("h");
        assertThat(stamped.findings()).isEqualTo("f");
        assertThat(stamped.conclusion()).isEqualTo("c");
    }

    @Test
    void withTemplateProvenancePreservesAuditGapDimensions() {
        NarrationResult original = new NarrationResult("h", "f", "c", null, null, 0.5, List.of(),
                "path", List.of(), List.of(), null, null, null, null,
                List.of("physical_mechanism"));
        NarrationResult stamped = original.withTemplateProvenance("tid", "v1");
        assertThat(stamped.auditGapDimensions()).containsExactly("physical_mechanism");
    }

    @Test
    void withDiscussionAndRelatedWorkPreservesAuditGapDimensions() {
        NarrationResult original = new NarrationResult("h", "f", "c", null, null, 0.5, List.of(),
                "path", List.of(), List.of(), null, null, null, null,
                List.of("physical_mechanism"));
        NarrationResult stamped = original.withDiscussionAndRelatedWork("d", "rw");
        assertThat(stamped.auditGapDimensions()).containsExactly("physical_mechanism");
    }
}
