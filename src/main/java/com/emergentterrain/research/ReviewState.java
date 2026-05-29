package com.emergentterrain.research;

/**
 * 2026-05-29 — pipeline / quality-health axis for a published paper, orthogonal
 * to the scientific-outcome axis ({@link ResultClassification}) and to the
 * back-compat {@code status} string ({@code PUBLISHED} / {@code NEEDS_REVISION} /
 * {@code NULL_RESULT} / {@code REJECTED} / …).
 *
 * <p>The single {@code status} string historically smashed two independent
 * questions onto one field — <em>what the science said</em> and <em>how the
 * paper came out of the pipeline</em> — which is why {@code NEEDS_REVISION} and
 * {@code NULL_RESULT} each conflate very different things:
 * <ul>
 *   <li>a {@code NEEDS_REVISION} paper can be sound-but-unfinished (a rec #8
 *       timeout salvage, a token-truncated draft) <em>or</em> carry a genuine
 *       content defect (a mislabel, an overclaim);</li>
 *   <li>a {@code NULL_RESULT} can be a genuine scientific null <em>or</em> a
 *       correct paper a validator mis-flagged (the harness false-positive
 *       population the 2026-05-28 scale/percent reconcile work targets).</li>
 * </ul>
 *
 * <p>{@code reviewState} captures the pipeline-health half so the two can be
 * told apart by a runtime consumer rather than by parsing the {@code callPath}
 * stamp. The high-value {@code (status, reviewState)} cells:
 * <ul>
 *   <li>{@code (NEEDS_REVISION, INCOMPLETE)} — re-queue candidate (the work just
 *       did not finish; the content may be sound).</li>
 *   <li>{@code (NEEDS_REVISION, REVISABLE_DEFECT)} — human revision (a real,
 *       fixable defect).</li>
 *   <li>{@code (NULL_RESULT, GATE_FLAGGED)} — likely harness false-positive.</li>
 *   <li>{@code (NULL_RESULT, CLEAN)} — genuine scientific null.</li>
 * </ul>
 *
 * <p>Persisted as the enum {@link #name()} in the nullable {@code review_state}
 * column; pre-2026-05-29 rows read back {@code null}.
 */
public enum ReviewState {

    /** Published cleanly, or a genuine scientific null — no pipeline/quality issue. */
    CLEAN,

    /**
     * The narration/review pipeline did not finish: a wall-clock-timeout
     * first-pass salvage (rec #8), a mid-sentence truncation, a missing-Methods
     * downgrade (no tool trace), a degraded review panel, or a paper published
     * without the tool-calling professor. The content before the cutoff may be
     * entirely sound — this is the {@link #isReworkCandidate() re-work} cell, not
     * a quality judgement on the science.
     */
    INCOMPLETE,

    /**
     * A content defect a focused revision could fix: a topic-specific mislabel,
     * an overclaim, an unsourced/uncited numeric, a single-point sweep described
     * as a range, a polarity inversion, a fabricated citation, a grade-floor
     * panel demotion, etc.
     */
    REVISABLE_DEFECT,

    /**
     * A validator or audit hard-flagged the paper — a {@code REJECTED} status, or
     * a {@code NULL_RESULT} that a gate <em>forced</em> (e.g. the
     * numerical-consistency validator in {@code downgrade} mode). This is the
     * cell where harness false-positives accumulate, so measuring it directly is
     * the honest read on the validator false-positive rate.
     */
    GATE_FLAGGED;

    /**
     * True when the paper's content is plausibly sound and only the pipeline was
     * cut short — the population an automated revision re-queue should target
     * (vs {@link #REVISABLE_DEFECT}, which needs a real content fix, and
     * {@link #GATE_FLAGGED}, which a validator deliberately blocked).
     */
    public boolean isReworkCandidate() {
        return this == INCOMPLETE;
    }

    /**
     * reviewState for a {@code NULL_RESULT} paper: {@link #GATE_FLAGGED} when a
     * gate forced the null (likely false-positive), else {@link #CLEAN} (a
     * genuine scientific null is a real, clean outcome).
     */
    public static ReviewState forNull(boolean forcedByGate) {
        return forcedByGate ? GATE_FLAGGED : CLEAN;
    }

    /**
     * reviewState for a paper demoted to {@code NEEDS_REVISION} by the review
     * panel. Both the degraded-panel gate and the grade-floor gate share the
     * {@code :demoted_to_needs_revision} callPath suffix, but they mean different
     * things: a <em>degraded</em> panel (fewer reviewers than configured ran) is
     * {@link #INCOMPLETE}, while a full panel that hit the <em>grade floor</em>
     * is a {@link #REVISABLE_DEFECT} (the reviewers graded the content low).
     */
    public static ReviewState forPanelDemotion(String callPath) {
        if (callPath != null && callPath.contains(":degraded:")) return INCOMPLETE;
        return REVISABLE_DEFECT;   // grade_floor (or any other full-panel demotion)
    }
}
