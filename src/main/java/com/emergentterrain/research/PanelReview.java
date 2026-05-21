package com.emergentterrain.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 7 F7.1 — one reviewer LLM's structured assessment of a paper's
 * computational record. Produced by the panel orchestrator (F7.2): the
 * orchestrator stamps {@link #reviewerId} based on which {@code providerKey/model}
 * handled the call; the rest of the fields come from the reviewer LLM's JSON.
 *
 * <p>The shape mirrors the spec entry under F7 in
 * {@code phases-for-human-like-performance/phase-7-multi-provider-and-math-expansion.md}:
 * grades on four axes, lists of which trace entries the reviewer judged
 * load-bearing vs. omitted, contradictions between the prose and the trace,
 * and free-form recommendations for the revision turn.
 *
 * <p>Phase 23.6 panel-emission slice: reviewers additionally propose an
 * {@link EvidenceLabel} via the JSON's optional {@code evidence_label} field.
 * Legacy reviewers (no field, blank value, or unrecognized label) yield
 * {@link Optional#empty()}; the {@link PanelOutcome} aggregator then either
 * skips the reviewer's vote or aggregates across whoever did vote.
 *
 * <p>All collection fields are guaranteed non-null after {@link #parse(String, String)}
 * (empty list defaults). Numeric grade fields clamp to [0.0, 1.0] so a misbehaving
 * reviewer cannot poison the aggregation step downstream.
 */
public record PanelReview(
        String reviewerId,
        Grades grades,
        List<Integer> citedCalls,
        List<Integer> omittedRelevant,
        List<Contradiction> contradictions,
        List<String> recommendations,
        Optional<EvidenceLabel> proposedLabel) {

    /**
     * Convenience ctor for callers/tests pre-dating the Phase 23.6
     * panel-emission slice. Defaults {@link #proposedLabel} to
     * {@link Optional#empty()} — equivalent to a reviewer who didn't vote on
     * the evidence label.
     */
    public PanelReview(String reviewerId, Grades grades, List<Integer> citedCalls,
                       List<Integer> omittedRelevant, List<Contradiction> contradictions,
                       List<String> recommendations) {
        this(reviewerId, grades, citedCalls, omittedRelevant, contradictions,
                recommendations, Optional.empty());
    }

    private static final Logger log = LoggerFactory.getLogger(PanelReview.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Four-axis grade vector. F7.2 aggregates median across reviewers. */
    public record Grades(double honesty, double completeness, double rigor, double novelty) {

        /** Defensive constructor: clamp every axis to [0, 1] at construction time. */
        public Grades {
            honesty      = clamp01(honesty);
            completeness = clamp01(completeness);
            rigor        = clamp01(rigor);
            novelty      = clamp01(novelty);
        }

        public static Grades zero() { return new Grades(0.0, 0.0, 0.0, 0.0); }

        private static double clamp01(double v) {
            if (Double.isNaN(v)) return 0.0;
            if (v < 0.0) return 0.0;
            if (v > 1.0) return 1.0;
            return v;
        }
    }

    /**
     * A reviewer-flagged disagreement between one trace entry and the synthesis.
     * {@code traceIdx} is 1-based to match the index space the reviewer prompt
     * presents the trace under (see {@code NARRATION_PANEL_REVIEW} template).
     */
    public record Contradiction(int traceIdx, String vsProse) {}

    /**
     * Parse a reviewer LLM's raw JSON output into a {@link PanelReview}. Returns
     * {@link Optional#empty()} on any parse failure so F7.2's orchestrator can drop
     * the failing reviewer from the panel rather than poison the aggregation.
     *
     * <p>The parser is intentionally tolerant: missing collection fields default
     * to empty lists, missing grade fields default to 0.0, and unknown top-level
     * keys are silently ignored. Only a malformed JSON document or a missing
     * {@code grades} object causes the parse to fail.
     */
    public static Optional<PanelReview> parse(String rawJson, String reviewerId) {
        if (rawJson == null || rawJson.isBlank()) return Optional.empty();
        try {
            JsonNode root = MAPPER.readTree(rawJson.trim());
            if (!root.isObject()) return Optional.empty();
            Grades grades = parseGrades(root.path("grades"));
            if (grades == null) return Optional.empty();
            List<Integer> cited     = parseIntArray(root.path("cited_calls"));
            List<Integer> omitted   = parseIntArray(root.path("omitted_relevant"));
            List<Contradiction> con = parseContradictions(root.path("contradictions"));
            List<String> recs       = parseStringArray(root.path("recommendations"));
            Optional<EvidenceLabel> label = parseLabel(root.path("evidence_label"));
            return Optional.of(new PanelReview(
                    reviewerId, grades, cited, omitted, con, recs, label));
        } catch (Exception e) {
            log.warn("PanelReview.parse: reviewer={} raw_len={} failed: {}",
                    reviewerId, rawJson.length(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Phase 23.6 — extract the reviewer's proposed {@link EvidenceLabel} from
     * the JSON's optional {@code evidence_label} field. Missing, blank,
     * non-textual, or unrecognized values yield {@link Optional#empty()}.
     *
     * <p>Unlike {@link ProposedLabelParser#parse} — which scans free-form LLM
     * text for label hints behind a preamble like {@code "Label: X"} — Jackson
     * has already extracted the bare value here, so we just normalize and
     * resolve against the enum directly (case-insensitive, hyphen→underscore).
     */
    private static Optional<EvidenceLabel> parseLabel(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isTextual()) {
            return Optional.empty();
        }
        String raw = node.asText();
        if (raw == null || raw.isBlank()) return Optional.empty();
        String normalized = raw.trim().toUpperCase().replace('-', '_');
        for (EvidenceLabel label : EvidenceLabel.values()) {
            if (label.name().equals(normalized)) {
                return Optional.of(label);
            }
        }
        return Optional.empty();
    }

    private static Grades parseGrades(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        return new Grades(
                node.path("honesty").asDouble(0.0),
                node.path("completeness").asDouble(0.0),
                node.path("rigor").asDouble(0.0),
                node.path("novelty").asDouble(0.0));
    }

    private static List<Integer> parseIntArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Integer> out = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            if (entry.isInt() || entry.isLong()) {
                out.add(entry.intValue());
            } else if (entry.isTextual()) {
                try { out.add(Integer.parseInt(entry.asText().trim())); }
                catch (NumberFormatException ignored) {}
            }
        }
        return List.copyOf(out);
    }

    private static List<String> parseStringArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            if (entry.isTextual()) {
                String s = entry.asText().trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static List<Contradiction> parseContradictions(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Contradiction> out = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            if (!entry.isObject()) continue;
            int idx = entry.path("trace_idx").asInt(-1);
            String vs = entry.path("vs_prose").asText("").trim();
            if (idx >= 0 && !vs.isEmpty()) out.add(new Contradiction(idx, vs));
        }
        return List.copyOf(out);
    }
}
