package com.emergentterrain.research.conjecture;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Flags for {@link ConjectureMinerService}, bound from {@code app.research.conjectureMiner.*}
 * (Spring relaxed binding maps the camelCase YAML keys onto these fields).
 *
 * <p>Collapses what used to be ~14 positional {@code @Value} constructor parameters into one
 * injected object: the service constructor stays small, and adding a new flag (e.g. the
 * remaining Wave-4 seed-replication knob) no longer forces every {@code new
 * ConjectureMinerService(...)} test call site to change — tests build a
 * {@code ConjectureMinerProperties} with defaults and override only what they exercise.
 *
 * <p>Defaults here match the old {@code @Value} code defaults (everything OFF / dormant); the
 * production YAML overrides them. Does NOT cover {@code enabled} (gates bean creation),
 * {@code intervalMs} (the {@code @Scheduled} SpEL string), or {@code paperShare} /
 * {@code allowedSubstrates} (read by {@link ConjectureTopicProvider}) — those bind elsewhere.
 */
@Component
@ConfigurationProperties("app.research.conjecture-miner")
public class ConjectureMinerProperties {

    // Wave 1 — cheap rejection gates.
    private boolean trivialityFilterEnabled = false;
    private boolean dimensionFilterEnabled = false;
    private boolean unitConsistencyEnabled = false;
    private boolean identifiabilityEnabled = false;
    private double identifiabilityMaxRelStdErr = 1.0;
    // 2026-06-14 — surgical incremental re-seed. The full seed() only fires on an EMPTY pool
    // (mine() store.isEmpty()), so a base-vocabulary addition (e.g. wigert_bound) never enters a
    // populated pool until a destructive wipe. When ON, each pass also seeds the genuinely-NEW
    // (lhs,rhs) vocabulary pairs absent from the pool entirely (live AND retired) into the live
    // store — activating new scalars without a wipe and preserving verified survivors. New-only by
    // design (never re-floods retired pairs), so it does not change paper-type mix (paperShare-gated)
    // or volume — it adds a handful of high-value candidates. Self-limiting: once the new pairs
    // persist, later passes find 0 new and no-op.
    private boolean incrementalReseedEnabled = false;
    // 2026-06-14 — knob-filter exemption for investigation-verified growth anchors (room b72c4e10
    // review-c Rec 1). A growth-anchor bound (rhs a window-geometry scalar) has window-correlated
    // slack by construction, so the knob-restatement heuristic was retiring verified survivors (the
    // durable 0.649 Cramér bound). When ON, a verified bound whose rhs ∈ WINDOW_GEOMETRY_SCALARS is
    // held aside from the knob filter (still counterexample-checked) — like the composition exemption,
    // gated on investigation-verified so spurious unverified window-correlated seeds are still dropped.
    private boolean verifiedAnchorKnobExemptEnabled = false;
    // 2026-06-14 — Phase 30 G1 (open-behavior-generation-activation.md). When ON, the miner drops
    // SLOPED affine bounds on settled-leading-order objects (mean_omega/mean_Omega ~ ln ln x,
    // mean_divisor_count ~ ln x, the densities) whose rhs is a flat feature — the trivial-refutation
    // class that dominates the corpus — keeping growth-anchor and k≈0 constant bounds. Shifts the
    // topic mix toward open-behavior / second-order targets. See ScalarOpenness.
    private boolean opennessPriorEnabled = false;
    // 2026-06-15 — Phase 30 G4 (open-behavior-generation-activation.md). When ON, conjecture SELECTION
    // (nextN, both tiers) promotes the actively-progressing open / second-order / growth-capture LHS
    // (ScalarOpenness.PRIORITY_TARGETS) to the front, with the existing rotation / clade order as the
    // within-bucket tiebreak. The post-G1 bottleneck was selection — the clean pool surfaced
    // growing-LHS / open topics only by luck. Down-weights the over-measured Mertens envelope (not a
    // priority target). See ScalarOpenness.selectionRank.
    private boolean opennessSelectionEnabled = false;
    // 2026-06-15 — Phase 30 G4 within-bucket diversity (open-behavior-generation-activation.md). The
    // bucket-level prior (opennessSelectionEnabled) promoted priority targets but over-concentrated on
    // the most-seeded family (max_prime_gap monoculture) and couldn't reach LIVE-only targets like
    // dirichlet_error (tier-1 retired always served before tier-2 live). When ON, nextN orders the
    // merged servable pool by [openness rank, least-recently-investigated LHS family, retired-first,
    // recency] — so distinct priority LHS rotate and live priority targets are reachable. See
    // ScalarOpenness + ConjectureMinerService.diversifiedNextN.
    private boolean opennessDiversityEnabled = false;
    // 2026-06-30 — Phase 30 G4 (open-behavior-generation-activation.md) quality-diversity retention into
    // selection (30.3). When ON, nextN collapses the servable pool to one elite (most-confirmed) per
    // behavioural cell = [openness rank : relation descriptorKey], so a flood of near-identical bounds
    // can't each consume a paper slot while open / second-order probes keep their own cell. Complements
    // the per-pass reduce() dedup (which doesn't span the accumulated repo pool) and preserves the
    // cross-call family rotation. Code default OFF; flipped ON in yaml 2026-06-30 (operator-directed) —
    // watch the "qd selection — N rows → M cells" served-topic-diversity log. Rollback: false.
    // See QualityDiversityArchive + ConjectureMinerService.nextN.
    private boolean qdSelectionEnabled = false;
    // 2026-07-04 (run-19: P-768/772 = the same cramer bound served twice in ONE run, both credited
    // ≥0.7 novelty; P-770 = P-760's exact bound re-served 3h later) — served-descriptor recency
    // gate. A descriptorKey consumed (markInvestigated) within the last servedDescriptorRecencyHours
    // is skipped by nextN, so the SAME BOUND cannot become a second paper while its first is fresh —
    // family rotation still revisits the FAMILY with a different relation. In-memory map (rooms
    // reset per app session anyway); a never-starve guard falls back to the unfiltered pool if the
    // filter would empty it. Code default OFF; flip in yaml. Rollback: false.
    private boolean servedDescriptorRecencyEnabled = false;
    private int servedDescriptorRecencyHours = 24;
    // 2026-07-05 (P-b72c-77627779 = P-b72c-77627766's descriptor re-served 14h later) — the
    // ledger was in-memory only, and the restart-per-run cadence wiped it, making the 24h window
    // effectively per-run. Non-blank path = load at construction (entries older than the window
    // are dropped) and rewrite on every markInvestigated. Blank = in-memory only (old behavior).
    private String servedDescriptorRecencyStatePath = "";
    // 2026-06-15 — Rec 2: exempt oscillating open targets (dirichlet_error = Δ(x)/x^{1/4}) from the FIM
    // slope_unpinned drop. Their affine slope is unpinned by nature (they swing ±), so FIM was retiring
    // every seed, leaving the open Dirichlet target unreachable through the queue. ON keeps a seed live
    // + servable; the professor pivots to verify_oscillation when it's served. See ScalarOpenness.
    private boolean oscillatingTargetFimExemptEnabled = false;
    // 2026-06-19 (novelty-funnel diagnosis) — measurement-target seed injector. An OSCILLATING open
    // target (dirichlet_error = Δ(x)/x^{1/4}) has no valid one-sided bound, so ConjectureEngine.seed never
    // produces a row for it (and seed runs only on an empty store), leaving the novelty flagship
    // unreachable despite being selection-rank 0 everywhere. ON injects a servable measurement-seed row
    // (idempotent: only when no servable row exists) so the existing rotation surfaces it and Stage-1f
    // reframes it to a verify_oscillation envelope investigation. ADDS an opportunity (no seed filtering).
    private boolean measurementTargetSeedEnabled = false;
    // Wave 0 — investigation rotation.
    private boolean investigationRotationEnabled = false;
    // 2026-06-10 — decision A (discovery→pool write-back, step 2). When ON, a row stamped
    // investigationVerifiedAt (a professor proved its bound HELD at the failure region via the
    // discovery→pool write-back) is spared from EMPIRICAL retirement on sub-failure-region evidence:
    // a counterexample below FAILURE_REGION_FLOOR no longer retires it (the bound is asymptotic —
    // the professor only ever claimed it at high x), and the stochastic-null / private-holdout /
    // replication heuristics defer to that verification. STRUCTURAL gates (knob/triviality/dimension/
    // unit) still apply, and a genuine counterexample AT x >= FAILURE_REGION_FLOOR still retires it.
    // Default OFF; closes the loop where every write-back revival was erased on the next mine pass.
    private boolean respectInvestigationVerified = false;
    // Wave 3 — clade-value × inverse-popularity selection (replaces rank-by-fit for live conjectures).
    private boolean cladeValueSelectionEnabled = false;
    // Observability.
    private boolean substrateMetricsEnabled = false;
    // Wave 2 — composition persistence.
    private boolean compositionPersistEnabled = false;
    private int compositionPersistCap = 5;
    private boolean compositionKnobExemptEnabled = false;
    // Wave 3 — sublinear generation budget (throttles composition vs validations performed).
    private boolean sublinearBudgetEnabled = false;
    private double sublinearBudgetAlpha = 0.5;
    // Wave 5 — residual-structure discovery (30.2); reuses the W4 null params for its test.
    private boolean residualStructureEnabled = false;
    // 2026-06-15 — Phase 30 G2 Stage 3. When ON (and residualStructure is on), a residual-discovered
    // lhs ~ rhs + z relation is PROMOTED from a log line to a persisted two-term conjecture
    // (lhs ≤ k1*rhs + k2*z + c, fit via tightestK1K2C) — generated second-order structure absent from
    // either single-term parent. Requires the rhs2/k2 columns (G2 DDL). OFF = residual stays log-only.
    private boolean twoTermEnabled = false;
    // Wave 5 — anomaly mining (30.1); reuses stochasticNullAlpha + multipleComparisonsMethod.
    private boolean anomalyMiningEnabled = false;
    private int anomalyMinConfirmed = 5;
    /** Phase 30 Wave 7 — 30.6 anomaly→open-problem promotion (adversarial gate + paper). */
    private boolean anomalyPromotionEnabled = false;
    private int promotionRefutationAttempts = 3;
    // Wave 5 — vocabulary expansion (30.5 P3a admission); reuses stochasticNull* + sublinearBudgetAlpha.
    private boolean vocabularyExpansionEnabled = false;
    private int vocabularyExpansionDepth = 1;
    // Stage C (novelty-ceiling-and-vocab-activation.md) — type-aware MDL description code (operator
    // nodes priced by their own small alphabet, not the full scalar+op alphabet). More accurate; lowers
    // the description cost for compositions so the genuinely-compressing below_description_cost bucket
    // can admit. Noise rejection (permutation null) is unaffected.
    private boolean vocabTypeAwareDescriptionCost = false;
    // Wave 6 — RAF-closure reporting (sim #1): report iRAF publishable units + ungrounded findings.
    private boolean rafClosureEnabled = false;
    // Wave 4 — SHADOW mode: when a W4 retirement gate's own flag is OFF, run it anyway and LOG what it
    // WOULD retire (no persistRetired, nothing removed) — the safe A/B path before flipping a gate live.
    private boolean w4ShadowEnabled = false;
    // Wave 4 component 1 — generative stochastic null.
    private boolean stochasticNullEnabled = false;
    private int stochasticNullPermutations = 999;
    private double stochasticNullAlpha = 0.05;
    private double stochasticNullMinCv = 0.01;
    // Wave 4 component 2 — multiple-comparisons correction.
    private boolean multipleComparisonsEnabled = false;
    private String multipleComparisonsMethod = "bh";
    // Wave 4 component 3 — private held-out validator + gaming rate.
    private boolean privateValidatorEnabled = false;
    // Wave 4 component 4 — ensemble replication (K-fold CV stability) + stochastic-pass.
    private boolean replicationValidatorEnabled = false;
    private int replicationFolds = 5;
    private double replicationMinPassRate = 0.8;

    public boolean isTrivialityFilterEnabled() { return trivialityFilterEnabled; }
    public void setTrivialityFilterEnabled(boolean v) { this.trivialityFilterEnabled = v; }

    public boolean isIncrementalReseedEnabled() { return incrementalReseedEnabled; }
    public void setIncrementalReseedEnabled(boolean v) { this.incrementalReseedEnabled = v; }

    public boolean isVerifiedAnchorKnobExemptEnabled() { return verifiedAnchorKnobExemptEnabled; }
    public void setVerifiedAnchorKnobExemptEnabled(boolean v) { this.verifiedAnchorKnobExemptEnabled = v; }

    public boolean isOpennessPriorEnabled() { return opennessPriorEnabled; }
    public void setOpennessPriorEnabled(boolean v) { this.opennessPriorEnabled = v; }

    public boolean isOpennessSelectionEnabled() { return opennessSelectionEnabled; }
    public void setOpennessSelectionEnabled(boolean v) { this.opennessSelectionEnabled = v; }

    public boolean isOpennessDiversityEnabled() { return opennessDiversityEnabled; }
    public void setOpennessDiversityEnabled(boolean v) { this.opennessDiversityEnabled = v; }

    public boolean isQdSelectionEnabled() { return qdSelectionEnabled; }
    public void setQdSelectionEnabled(boolean v) { this.qdSelectionEnabled = v; }

    public boolean isServedDescriptorRecencyEnabled() { return servedDescriptorRecencyEnabled; }
    public void setServedDescriptorRecencyEnabled(boolean v) { this.servedDescriptorRecencyEnabled = v; }
    public int getServedDescriptorRecencyHours() { return servedDescriptorRecencyHours; }
    public void setServedDescriptorRecencyHours(int v) { this.servedDescriptorRecencyHours = v; }
    public String getServedDescriptorRecencyStatePath() { return servedDescriptorRecencyStatePath; }
    public void setServedDescriptorRecencyStatePath(String v) { this.servedDescriptorRecencyStatePath = v; }

    public boolean isOscillatingTargetFimExemptEnabled() { return oscillatingTargetFimExemptEnabled; }
    public void setOscillatingTargetFimExemptEnabled(boolean v) { this.oscillatingTargetFimExemptEnabled = v; }

    public boolean isMeasurementTargetSeedEnabled() { return measurementTargetSeedEnabled; }
    public void setMeasurementTargetSeedEnabled(boolean v) { this.measurementTargetSeedEnabled = v; }

    public boolean isDimensionFilterEnabled() { return dimensionFilterEnabled; }
    public void setDimensionFilterEnabled(boolean v) { this.dimensionFilterEnabled = v; }

    public boolean isUnitConsistencyEnabled() { return unitConsistencyEnabled; }
    public void setUnitConsistencyEnabled(boolean v) { this.unitConsistencyEnabled = v; }

    public boolean isIdentifiabilityEnabled() { return identifiabilityEnabled; }
    public void setIdentifiabilityEnabled(boolean v) { this.identifiabilityEnabled = v; }

    public double getIdentifiabilityMaxRelStdErr() { return identifiabilityMaxRelStdErr; }
    public void setIdentifiabilityMaxRelStdErr(double v) { this.identifiabilityMaxRelStdErr = v; }

    public boolean isInvestigationRotationEnabled() { return investigationRotationEnabled; }
    public void setInvestigationRotationEnabled(boolean v) { this.investigationRotationEnabled = v; }

    public boolean isRespectInvestigationVerified() { return respectInvestigationVerified; }
    public void setRespectInvestigationVerified(boolean v) { this.respectInvestigationVerified = v; }

    public boolean isCladeValueSelectionEnabled() { return cladeValueSelectionEnabled; }
    public void setCladeValueSelectionEnabled(boolean v) { this.cladeValueSelectionEnabled = v; }

    public boolean isSubstrateMetricsEnabled() { return substrateMetricsEnabled; }
    public void setSubstrateMetricsEnabled(boolean v) { this.substrateMetricsEnabled = v; }

    public boolean isCompositionPersistEnabled() { return compositionPersistEnabled; }
    public void setCompositionPersistEnabled(boolean v) { this.compositionPersistEnabled = v; }

    public int getCompositionPersistCap() { return compositionPersistCap; }
    public void setCompositionPersistCap(int v) { this.compositionPersistCap = v; }

    public boolean isCompositionKnobExemptEnabled() { return compositionKnobExemptEnabled; }
    public void setCompositionKnobExemptEnabled(boolean v) { this.compositionKnobExemptEnabled = v; }

    public boolean isSublinearBudgetEnabled() { return sublinearBudgetEnabled; }
    public void setSublinearBudgetEnabled(boolean v) { this.sublinearBudgetEnabled = v; }

    public double getSublinearBudgetAlpha() { return sublinearBudgetAlpha; }
    public void setSublinearBudgetAlpha(double v) { this.sublinearBudgetAlpha = v; }

    public boolean isW4ShadowEnabled() { return w4ShadowEnabled; }
    public void setW4ShadowEnabled(boolean v) { this.w4ShadowEnabled = v; }

    public boolean isResidualStructureEnabled() { return residualStructureEnabled; }
    public void setResidualStructureEnabled(boolean v) { this.residualStructureEnabled = v; }

    public boolean isTwoTermEnabled() { return twoTermEnabled; }
    public void setTwoTermEnabled(boolean v) { this.twoTermEnabled = v; }

    public boolean isAnomalyMiningEnabled() { return anomalyMiningEnabled; }
    public void setAnomalyMiningEnabled(boolean v) { this.anomalyMiningEnabled = v; }

    public int getAnomalyMinConfirmed() { return anomalyMinConfirmed; }
    public void setAnomalyMinConfirmed(int v) { this.anomalyMinConfirmed = v; }

    public boolean isAnomalyPromotionEnabled() { return anomalyPromotionEnabled; }
    public void setAnomalyPromotionEnabled(boolean v) { this.anomalyPromotionEnabled = v; }

    public int getPromotionRefutationAttempts() { return promotionRefutationAttempts; }
    public void setPromotionRefutationAttempts(int v) { this.promotionRefutationAttempts = v; }

    public boolean isVocabularyExpansionEnabled() { return vocabularyExpansionEnabled; }
    public void setVocabularyExpansionEnabled(boolean v) { this.vocabularyExpansionEnabled = v; }

    public int getVocabularyExpansionDepth() { return vocabularyExpansionDepth; }
    public void setVocabularyExpansionDepth(int v) { this.vocabularyExpansionDepth = v; }

    public boolean isVocabTypeAwareDescriptionCost() { return vocabTypeAwareDescriptionCost; }
    public void setVocabTypeAwareDescriptionCost(boolean v) { this.vocabTypeAwareDescriptionCost = v; }

    public boolean isRafClosureEnabled() { return rafClosureEnabled; }
    public void setRafClosureEnabled(boolean v) { this.rafClosureEnabled = v; }

    public boolean isStochasticNullEnabled() { return stochasticNullEnabled; }
    public void setStochasticNullEnabled(boolean v) { this.stochasticNullEnabled = v; }

    public int getStochasticNullPermutations() { return stochasticNullPermutations; }
    public void setStochasticNullPermutations(int v) { this.stochasticNullPermutations = v; }

    public double getStochasticNullAlpha() { return stochasticNullAlpha; }
    public void setStochasticNullAlpha(double v) { this.stochasticNullAlpha = v; }

    public double getStochasticNullMinCv() { return stochasticNullMinCv; }
    public void setStochasticNullMinCv(double v) { this.stochasticNullMinCv = v; }

    public boolean isMultipleComparisonsEnabled() { return multipleComparisonsEnabled; }
    public void setMultipleComparisonsEnabled(boolean v) { this.multipleComparisonsEnabled = v; }

    public String getMultipleComparisonsMethod() { return multipleComparisonsMethod; }
    public void setMultipleComparisonsMethod(String v) { this.multipleComparisonsMethod = v; }

    public boolean isPrivateValidatorEnabled() { return privateValidatorEnabled; }
    public void setPrivateValidatorEnabled(boolean v) { this.privateValidatorEnabled = v; }

    public boolean isReplicationValidatorEnabled() { return replicationValidatorEnabled; }
    public void setReplicationValidatorEnabled(boolean v) { this.replicationValidatorEnabled = v; }

    public int getReplicationFolds() { return replicationFolds; }
    public void setReplicationFolds(int v) { this.replicationFolds = v; }

    public double getReplicationMinPassRate() { return replicationMinPassRate; }
    public void setReplicationMinPassRate(double v) { this.replicationMinPassRate = v; }
}
