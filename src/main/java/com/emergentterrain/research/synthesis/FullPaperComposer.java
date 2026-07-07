package com.emergentterrain.research.synthesis;

import com.emergentterrain.research.literature.GroundingClient;

import java.util.List;

/**
 * Phase 33 lane D (D3, 2026-07-07) — the full-paper pass over a published synthesis, re-scoped
 * synthesis-first per the 2026-07-07 design discussion and the closing-condition diagnosis: the
 * C2 synthesis pipeline has no literature tools, so a synthesis could not satisfy the L-lane —
 * D3 is not presentation polish but the route by which program-grade results become
 * deliverable-grade papers.
 *
 * <p>Pure prompt/assembly helpers (static, testable); orchestration and the two LLM calls live
 * in {@link SynthesisPaperService#composeFullPaper}. The paper shape codifies what EMERGED as
 * the best observed structure (the twin_vs and max_prime_gap fit syntheses): grounded
 * introduction + related work (with honest SILENT-novelty claims), the synthesis body as the
 * results program, provenance stated, open questions last.
 */
public final class FullPaperComposer {

    private FullPaperComposer() {
    }

    static final int MAX_READ_PASSAGE_CHARS = 3_000;

    /**
     * The intro + related-work prompt. Grounding snippets and (when cores were on disk) actual
     * read passages ride in; the section must position the program's headline claim against
     * them — or state the SILENT-novelty claim honestly.
     */
    public static String buildIntroRelatedWorkPrompt(String family, String headlineClaim,
            String groundingBlock, String readPassages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Write the \"Introduction and Related Work\" section of a research paper "
                + "presenting a computational program's results on ").append(family).append(".\n\n"
                + "The program's headline claim:\n  ").append(headlineClaim).append("\n\n");
        if (groundingBlock != null && !groundingBlock.isBlank()) {
            sb.append("Literature retrieval over the corpus (cite ONLY these keys, as [key]):\n")
                    .append(groundingBlock).append('\n');
        }
        if (readPassages != null && !readPassages.isBlank()) {
            sb.append("\nFULL-TEXT PASSAGES from retrieved works (position the claim against "
                    + "their ACTUAL content — supports / contradicts / adjacent-but-distinct; "
                    + "if a passage came from PDF extraction, paraphrase rather than quote "
                    + "formulas):\n").append(readPassages).append('\n');
        }
        sb.append("\nRules: motivate the question in 2-3 sentences a number theorist would "
                + "accept; position the headline claim against the retrieved work explicitly; "
                + "if retrieval was SILENT, state plainly that a corpus search found no prior "
                + "statement of the claim (that is a finding); make NO claim beyond the "
                + "retrieval and passages above; no invented citations.\n"
                + "OUTPUT FORMAT — CRITICAL: write ONLY the finished section prose. Do not "
                + "think out loud, restate this task, or add preamble. <= 1800 chars.");
        return sb.toString();
    }

    /** The abstract prompt — one paragraph over the assembled results body. */
    public static String buildAbstractPrompt(String title, String bodyMarkdown) {
        return "Write the ABSTRACT (one paragraph, <= 900 chars) for a research paper titled \""
                + title + "\" whose full results body follows. State: the question, the "
                + "instrument (windowed computational verification), the quantitative result "
                + "with its actual numbers, and the open question. No hype; every number must "
                + "appear in the body.\nOUTPUT FORMAT — CRITICAL: only the abstract paragraph, "
                + "no preamble.\n\nBODY:\n" + bodyMarkdown;
    }

    /** Compact rendering of grounding evidence for the intro prompt. */
    public static String renderGroundingBlock(GroundingClient.GroundingResult grounding) {
        if (grounding == null || grounding.evidence() == null || grounding.evidence().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("verdict: ").append(grounding.verdict()).append('\n');
        for (GroundingClient.Evidence e : grounding.evidence()) {
            sb.append("  [").append(e.key()).append("] ").append(e.title())
                    .append(" (").append(e.year()).append("): ")
                    .append(e.snippet() == null ? "" : e.snippet()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Assemble the final paper markdown. The synthesis body becomes the results program;
     * provenance names the machine layers so a reader knows what "verified" means here.
     */
    public static String assemble(String title, String abstractText, String introRelatedWork,
            String synthesisBody, List<String> citedIds, String family) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append('\n');
        sb.append("\n**Kind**: FULL_PAPER (D3 promotion of a program synthesis)  |  "
                + "**Family**: ").append(family).append('\n');
        sb.append("\n## Abstract\n\n").append(strip(abstractText)).append('\n');
        sb.append("\n## Introduction and Related Work\n\n").append(strip(introRelatedWork))
                .append('\n');
        sb.append("\n## Results: the program\n").append(synthesisBody).append('\n');
        sb.append("\n## Provenance\n\n"
                + "Every quantitative claim above is drawn from machine-verified tool verdicts "
                + "recorded in a cross-run results ledger, cited inline by the paper that "
                + "produced it (").append(citedIds == null ? 0 : citedIds.size())
                .append(" internal sources). Bounds marked as binding pairs carry both the "
                + "tight hold and its refuting counterpart. Literature positioning in the "
                + "introduction is limited to retrieved corpus entries and, where available, "
                + "their stored full texts.\n");
        return sb.toString();
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
