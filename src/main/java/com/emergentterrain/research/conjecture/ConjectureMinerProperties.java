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
    // Wave 0 — investigation rotation.
    private boolean investigationRotationEnabled = false;
    // Observability.
    private boolean substrateMetricsEnabled = false;
    // Wave 2 — composition persistence.
    private boolean compositionPersistEnabled = false;
    private int compositionPersistCap = 5;
    private boolean compositionKnobExemptEnabled = false;
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

    public boolean isSubstrateMetricsEnabled() { return substrateMetricsEnabled; }
    public void setSubstrateMetricsEnabled(boolean v) { this.substrateMetricsEnabled = v; }

    public boolean isCompositionPersistEnabled() { return compositionPersistEnabled; }
    public void setCompositionPersistEnabled(boolean v) { this.compositionPersistEnabled = v; }

    public int getCompositionPersistCap() { return compositionPersistCap; }
    public void setCompositionPersistCap(int v) { this.compositionPersistCap = v; }

    public boolean isCompositionKnobExemptEnabled() { return compositionKnobExemptEnabled; }
    public void setCompositionKnobExemptEnabled(boolean v) { this.compositionKnobExemptEnabled = v; }

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
