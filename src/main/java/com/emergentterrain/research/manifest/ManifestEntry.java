package com.emergentterrain.research.manifest;

/**
 * Phase 32 Wave 0 (32.1) — one provenance-carrying value in a paper draft's
 * verified-values manifest. The data-to-paper {@code ReferencedValue} pattern
 * reduced to ET's shape: ET already has call-level provenance ({@code [call_N]}
 * markers validated against the executed trace); the manifest adds the value
 * level, so a number in prose can be resolved to the exact tool output (or
 * hypothesis constant) that produced it instead of being re-derived by the
 * validator ladder's regex passes.
 *
 * @param label      stable within a draft: {@code m<call>_<seq>} for tool
 *                   outputs, {@code h_<seq>} for hypothesis constants
 * @param value      canonical parsed double
 * @param rendering  the exact literal as the source printed it (sign, grouping,
 *                   precision — e.g. {@code "+120.97163"}, {@code "1,441,000"})
 * @param producedBy {@code call_N} for tool outputs, {@code hypothesis} for
 *                   pre-registered constants
 * @param kind       coarse provenance class
 */
public record ManifestEntry(
        String label,
        double value,
        String rendering,
        String producedBy,
        Kind kind) {

    public enum Kind {
        /** Parsed from an executed tool call's result text. */
        TOOL_OUTPUT,
        /**
         * Parsed from an executed tool call's ARGUMENTS — preregistered
         * baselines, k/c, window coordinates. Inputs are provenance too
         * (P-b72c-241: "the pre-registered prediction of 0.140" lived only in
         * {@code baseline=0.140} of the scan args; the result never echoed it).
         */
        TOOL_INPUT,
        /** Parsed from the pre-registered hypothesis (k, c, ranges). */
        HYPOTHESIS_CONSTANT
    }
}
