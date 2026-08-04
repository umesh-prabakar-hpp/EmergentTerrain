package com.emergentterrain.research;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 7 F7.2 — aggregated outcome of a multi-reviewer panel.
 *
 * <p>Produced by {@code ReviewPanelService.runPanel(...)} from the N individual
 * {@link PanelReview}s the panel returned. The aggregation is deterministic — no
 * meta-reviewer LLM — so the result is reproducible from the same N inputs.
 *
 * <p>Aggregation rules (per the F7 add-on spec):
 * <ul>
 *   <li><b>aggregateGrades</b> — median across reviewers on each of the four axes.
 *       Median is robust to single-reviewer drift; mean would be too sensitive.</li>
 *   <li><b>agreementScore</b> — {@code 1 - mean(stddev of each axis)}, clamped
 *       to [0, 1]. High value = reviewers converged; low value = the paper is
 *       genuinely contested. F7.3 will route low-agreement papers to DISPUTED.</li>
 *   <li><b>consensusCitedCalls</b> — intersection of every reviewer's
 *       {@code citedCalls} list. A trace entry is "consensus load-bearing" only
 *       if every panelist agreed it contributed.</li>
 *   <li><b>consensusContradictions</b> — contradictions flagged by ≥2
 *       reviewers, keyed by {@code traceIdx}. Single-reviewer contradictions
 *       are dropped as noise; the {@code vsProse} text is taken from the first
 *       reviewer that flagged it.</li>
 *   <li><b>recommendations</b> — deduplicated union across all reviewers,
 *       preserving first-seen order.</li>
 *   <li><b>reviews</b> — the raw N inputs, retained for the audit trail and so
 *       F7.3 can examine per-reviewer agreement on a specific dimension.</li>
 * </ul>
 *
 * <p>The empty outcome (no reviewers participated, panel was disabled, or every
 * reviewer dropped on parse failure) is signalled by {@link #isEmpty()}. The
 * publish path treats an empty outcome as "panel did not run" and bit-identical
 * to pre-F7.2 behaviour.
 */
public record PanelOutcome(
        PanelReview.Grades aggregateGrades,
        double agreementScore,
        List<Integer> consensusCitedCalls,
        /*
         * Phase 7 F7.3 — trace indices that >=2 reviewers flagged as omitted-but-relevant.
         * This is the cherry-picking signal: the LLM ran a tool call whose result was
         * relevant to the prose but didn't surface it. Non-empty list = the new
         * cherry_picked_evidence audit-gap dimension fires.
         */
        List<Integer> consensusOmittedRelevant,
        List<PanelReview.Contradiction> consensusContradictions,
        List<String> recommendations,
        List<PanelReview> reviews,
        /*
         * Phase 23.6 panel-emission slice — median-ordinal aggregation of the
         * per-reviewer {@link PanelReview#proposedLabel} votes, with the weaker
         * label winning even-N ties (earn the stronger claim by having
         * &ge;&lceil;N/2&rceil; reviewers vote that strong). Empty when no
         * reviewer proposed a label or when the panel itself is empty.
         */
        Optional<EvidenceLabel> proposedEvidenceLabel) {

    /**
     * Convenience ctor for callers/tests pre-dating the Phase 23.6
     * panel-emission slice. Defaults {@link #proposedEvidenceLabel} to
     * {@link Optional#empty()} — equivalent to no reviewer voting on the label.
     */
    public PanelOutcome(PanelReview.Grades aggregateGrades, double agreementScore,
                        List<Integer> consensusCitedCalls,
                        List<Integer> consensusOmittedRelevant,
                        List<PanelReview.Contradiction> consensusContradictions,
                        List<String> recommendations, List<PanelReview> reviews) {
        this(aggregateGrades, agreementScore, consensusCitedCalls,
                consensusOmittedRelevant, consensusContradictions, recommendations,
                reviews, Optional.empty());
    }

    public static PanelOutcome empty() {
        return new PanelOutcome(
                PanelReview.Grades.zero(),
                1.0,           // no disagreement when no reviewers
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Optional.empty());
    }

    public boolean isEmpty() {
        return reviews == null || reviews.isEmpty();
    }

    public int reviewerCount() {
        return reviews == null ? 0 : reviews.size();
    }

    /**
     * Aggregate N reviewers into one outcome. Null / empty input yields {@link #empty()}.
     */
    public static PanelOutcome aggregate(List<PanelReview> reviews) {
        if (reviews == null || reviews.isEmpty()) return empty();

        PanelReview.Grades agg = medianGrades(reviews);
        double agreement = computeAgreement(reviews);
        List<Integer> consensusCited = intersectCitedCalls(reviews);
        List<Integer> consensusOmitted = aggregateOmittedRelevant(reviews);
        List<PanelReview.Contradiction> consensusContradictions = aggregateContradictions(reviews);
        List<String> recs = dedupeRecommendations(reviews);
        Optional<EvidenceLabel> proposed = aggregateProposedLabel(reviews);
        return new PanelOutcome(agg, agreement, consensusCited, consensusOmitted,
                consensusContradictions, recs, List.copyOf(reviews), proposed);
    }

    /**
     * Phase 23.6 — aggregate the reviewer {@link PanelReview#proposedLabel}
     * votes into a single proposal for the publish path. Reviewers who didn't
     * vote (empty optional) are skipped. The aggregate is the median ordinal
     * with the <em>weaker</em> label winning even-N ties: the stronger claim
     * must be earned by &ge;&lceil;N/2&rceil; reviewers, mirroring the
     * LabelFloor's spirit that a higher label demands more evidence.
     *
     * <p>Returns {@link Optional#empty()} when no reviewer voted, so the
     * publish path falls back to {@code paper.evidenceLabel()}'s prior value
     * (defaults to {@link EvidenceLabel#CONJECTURE}).
     */
    private static Optional<EvidenceLabel> aggregateProposedLabel(List<PanelReview> reviews) {
        int[] ordinals = reviews.stream()
                .map(PanelReview::proposedLabel)
                .filter(o -> o != null && o.isPresent())
                .mapToInt(o -> o.get().ordinal())
                .sorted()
                .toArray();
        if (ordinals.length == 0) return Optional.empty();
        // Ordinals: THEOREM=0 (strongest) → UNEXPLAINED_OBSERVATION=3 (weakest).
        // Higher ordinal = weaker. For even N, take the higher of the two
        // middle ordinals — i.e., the weaker label wins ties.
        int idx = ordinals.length % 2 == 1
                ? ordinals.length / 2
                : ordinals.length / 2;
        return Optional.of(EvidenceLabel.values()[ordinals[idx]]);
    }

    private static PanelReview.Grades medianGrades(List<PanelReview> reviews) {
        return new PanelReview.Grades(
                median(reviews, r -> r.grades().honesty()),
                median(reviews, r -> r.grades().completeness()),
                median(reviews, r -> r.grades().rigor()),
                median(reviews, r -> r.grades().novelty()));
    }

    private static double median(List<PanelReview> reviews,
                                  java.util.function.ToDoubleFunction<PanelReview> f) {
        double[] sorted = reviews.stream().mapToDouble(f).sorted().toArray();
        int n = sorted.length;
        if (n == 0) return 0.0;
        if (n % 2 == 1) return sorted[n / 2];
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    /**
     * Composite agreement score: {@code 1 - mean(stddev_axis)}. Each axis's
     * stddev is computed across the N reviewers' grades on that axis; the four
     * axis-stddevs are then averaged. Subtracted from 1.0 so the score reads
     * "high = good agreement, low = high disagreement". Clamped to [0, 1] so
     * outlier reviewers can't push it negative.
     */
    private static double computeAgreement(List<PanelReview> reviews) {
        if (reviews.size() <= 1) return 1.0;
        double sigmaHonesty      = stddev(reviews, r -> r.grades().honesty());
        double sigmaCompleteness = stddev(reviews, r -> r.grades().completeness());
        double sigmaRigor        = stddev(reviews, r -> r.grades().rigor());
        double sigmaNovelty      = stddev(reviews, r -> r.grades().novelty());
        double meanStddev = (sigmaHonesty + sigmaCompleteness + sigmaRigor + sigmaNovelty) / 4.0;
        double score = 1.0 - meanStddev;
        if (score < 0.0) return 0.0;
        if (score > 1.0) return 1.0;
        return score;
    }

    private static double stddev(List<PanelReview> reviews,
                                  java.util.function.ToDoubleFunction<PanelReview> f) {
        double mean = reviews.stream().mapToDouble(f).average().orElse(0.0);
        double sumSq = 0.0;
        for (PanelReview r : reviews) {
            double v = f.applyAsDouble(r);
            double d = v - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / reviews.size());
    }

    private static List<Integer> intersectCitedCalls(List<PanelReview> reviews) {
        if (reviews.isEmpty()) return List.of();
        Set<Integer> acc = new java.util.LinkedHashSet<>(reviews.get(0).citedCalls());
        for (int i = 1; i < reviews.size(); i++) {
            acc.retainAll(new HashSet<>(reviews.get(i).citedCalls()));
        }
        return List.copyOf(acc);
    }

    /**
     * Phase 7 F7.3 — trace indices that ≥2 reviewers flagged as
     * omitted-but-relevant. Implements the cherry-picking signal: an entry
     * here means at least two independent reviewers agreed the LLM ignored
     * a tool result that should have appeared in the prose.
     */
    private static List<Integer> aggregateOmittedRelevant(List<PanelReview> reviews) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (PanelReview r : reviews) {
            for (Integer idx : r.omittedRelevant()) {
                if (idx == null) continue;
                counts.merge(idx, 1, Integer::sum);
            }
        }
        List<Integer> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() >= 2) out.add(e.getKey());
        }
        return List.copyOf(out);
    }

    private static List<PanelReview.Contradiction> aggregateContradictions(List<PanelReview> reviews) {
        // Group by traceIdx, count occurrences, keep those with >= 2.
        Map<Integer, List<PanelReview.Contradiction>> byIdx = new LinkedHashMap<>();
        for (PanelReview r : reviews) {
            for (PanelReview.Contradiction c : r.contradictions()) {
                byIdx.computeIfAbsent(c.traceIdx(), k -> new ArrayList<>()).add(c);
            }
        }
        List<PanelReview.Contradiction> out = new ArrayList<>();
        for (Map.Entry<Integer, List<PanelReview.Contradiction>> e : byIdx.entrySet()) {
            if (e.getValue().size() >= 2) {
                // Keep the first reviewer's vsProse text — they're typically similar
                // enough that a representative is more informative than a join.
                out.add(e.getValue().get(0));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> dedupeRecommendations(List<PanelReview> reviews) {
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (PanelReview r : reviews) {
            for (String rec : r.recommendations()) {
                if (rec == null) continue;
                String trimmed = rec.trim();
                if (!trimmed.isEmpty()) seen.putIfAbsent(trimmed, Boolean.TRUE);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(seen.keySet()));
    }

    /** Convenience getter for F7.3 — the only single-scalar metric persisted as a column. */
    public double reviewerAgreement() {
        return agreementScore;
    }

    /**
     * For the markdown renderer / audit: a compact map for diagnostic logging.
     * Not stable JSON — the persisted form uses Jackson on the record itself.
     */
    public Map<String, Object> summary() {
        Map<String, Object> m = new HashMap<>();
        m.put("reviewers", reviewerCount());
        m.put("agreement", agreementScore);
        m.put("median_honesty",      aggregateGrades.honesty());
        m.put("median_completeness", aggregateGrades.completeness());
        m.put("median_rigor",        aggregateGrades.rigor());
        m.put("median_novelty",      aggregateGrades.novelty());
        m.put("consensus_cited_calls",      consensusCitedCalls.size());
        m.put("consensus_contradictions",   consensusContradictions.size());
        m.put("recommendations",            recommendations.size());
        return m;
    }
}
