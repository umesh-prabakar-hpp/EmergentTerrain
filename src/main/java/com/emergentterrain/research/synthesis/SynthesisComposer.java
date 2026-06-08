package com.emergentterrain.research.synthesis;

import java.util.List;

/**
 * Phase 31 §31.7 — outline-first synthesis composition, the PURE prompt layer
 * (no router; testable like {@code ReviewAnchor}). The orchestrating service
 * (a later slice) calls these to drive: one outline call, then one call per
 * section seeded with a digest of the already-written sections (the
 * gpt-researcher similar-written-content trick that stops sections repeating
 * each other).
 *
 * <p>House contract baked into the prompts: every claim carries a
 * {@code [P-…]} / conjecture-id citation (the existing CitationValidator
 * applies to the result unchanged), and "the corpus does not yet support a
 * synthesis" is a first-class allowed outcome — a synthesis is never forced.
 */
public final class SynthesisComposer {

    private SynthesisComposer() {}

    /** One cited source paper, reduced to what a synthesis prompt needs. */
    public record SourcePaper(String paperId, String topic, String evidenceLabel,
                              double peerScore, String oneLineFinding) {}

    /**
     * The outline call: propose {@code minSections}–{@code maxSections}
     * section titles for a synthesis over the given family, grounded ONLY in
     * the cited corpus (and the compiled lesson if present) — never invented.
     */
    public static String buildOutlinePrompt(String domain, String family,
            String compiledLesson, List<SourcePaper> sources,
            int minSections, int maxSections) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are composing a SYNTHESIS (review) paper over a research room's "
                + "own prior papers on one topic family. Domain: ").append(domain)
                .append("; family: ").append(family).append(".\n\n");
        if (compiledLesson != null && !compiledLesson.isBlank()) {
            sb.append("Compiled cross-run lesson for this family:\n  ")
                    .append(compiledLesson.strip()).append("\n\n");
        }
        sb.append("The corpus to synthesize (cite ONLY these, by paperId):\n");
        appendSources(sb, sources);
        sb.append("\nPropose between ").append(minSections).append(" and ")
                .append(maxSections).append(" section titles that organize what this corpus "
                + "COLLECTIVELY says — agreements, conflicts, the boundary of what holds, and "
                + "the open question. Ground every section in the corpus above; invent nothing. "
                + "If the corpus does not yet support a coherent synthesis, respond with the "
                + "single line: NULL_SYNTHESIS: <one-sentence reason>.\n\n"
                + "Respond as a JSON array of section-title strings and nothing else, e.g. "
                + "[\"The shared ceiling\",\"Where the bounds disagree\",\"What remains open\"].");
        return sb.toString();
    }

    /**
     * A section-draft call: write {@code sectionTitle} grounded in the sources,
     * NOT repeating {@code priorSectionsDigest}. Every numeric/▢factual claim
     * must carry a {@code [paperId]} citation.
     */
    public static String buildSectionPrompt(String sectionTitle, String family,
            String priorSectionsDigest, List<SourcePaper> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write the section \"").append(sectionTitle).append("\" of a synthesis paper "
                + "on ").append(family).append(".\n\n");
        if (priorSectionsDigest != null && !priorSectionsDigest.isBlank()) {
            sb.append("ALREADY-WRITTEN sections (do NOT repeat these points; build on them):\n")
                    .append(priorSectionsDigest.strip()).append("\n\n");
        }
        sb.append("Cite ONLY these papers, by [paperId], for EVERY factual or numeric claim:\n");
        appendSources(sb, sources);
        sb.append("\nRules: every claim that names a number, a bound, or an outcome carries the "
                + "[paperId] it came from in the same sentence; make no claim the corpus does not "
                + "support; do not restate the already-written sections.\n"
                + "OUTPUT FORMAT — CRITICAL: write ONLY the finished section prose exactly as it "
                + "will appear in the published paper. Do NOT think out loud, plan, restate this "
                + "task, mention \"the section\"/\"we need to\"/\"already-written\", or add any "
                + "preamble or meta-commentary. Begin directly with the first sentence of the "
                + "section. <= 1200 chars.");
        return sb.toString();
    }

    /**
     * Defensive strip: a section draft that still leaks the model's planning
     * (despite the OUTPUT FORMAT rule) is detected and discarded by the
     * orchestrator. Returns true if the text reads as chain-of-thought rather
     * than section prose. (P-b72c synthesis #1: section 2 emitted "We need to
     * write the section … The task: …" verbatim.)
     */
    public static boolean looksLikeLeakedReasoning(String section) {
        if (section == null) return false;
        String head = section.stripLeading();
        String lower = head.toLowerCase(java.util.Locale.ROOT);
        // Markers of the model addressing the task instead of writing the paper.
        return lower.startsWith("we need to")
                || lower.startsWith("we should")
                || lower.startsWith("the task")
                || lower.startsWith("let's")
                || lower.startsWith("first, ")
                || lower.contains("write the section \"")
                || lower.contains("already-written sections")
                || lower.contains("we'll need to check");
    }

    private static void appendSources(StringBuilder sb, List<SourcePaper> sources) {
        if (sources != null) {
            for (SourcePaper s : sources) {
                sb.append("  [").append(s.paperId()).append("] ")
                        .append(s.topic()).append(" (").append(s.evidenceLabel())
                        .append(", peer ").append(String.format("%.2f", s.peerScore()))
                        .append("): ").append(s.oneLineFinding() == null ? ""
                                : s.oneLineFinding().strip()).append('\n');
            }
        }
    }

    /** Sentinel an outline response uses when no synthesis is warranted. */
    public static final String NULL_SYNTHESIS_PREFIX = "NULL_SYNTHESIS";

    public static boolean isNullSynthesis(String outlineResponse) {
        return outlineResponse != null
                && outlineResponse.stripLeading().toUpperCase(java.util.Locale.ROOT)
                        .startsWith(NULL_SYNTHESIS_PREFIX);
    }
}
