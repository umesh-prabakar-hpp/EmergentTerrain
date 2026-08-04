package com.emergentterrain.research;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Resolves the reader-visible workflow state without conflating it with the
 * deterministic scientific result classification.
 *
 * <p>A result can be semantically supported while still needing revision for
 * independently auditable reasons: an acquired source was not cited, a
 * predeclared sensitivity check is absent, or an independent replication is
 * absent. Conversely, those defects must never relabel a genuine scientific
 * null as a positive or negative scientific finding.
 */
public final class PublicationStateResolver {

    public enum WorkflowState {
        PUBLISHED,
        PUBLISHED_NO_PROFESSOR,
        NEEDS_REVISION,
        REJECTED
    }

    public enum Deficit {
        CITATION,
        SENSITIVITY,
        REPLICATION
    }

    public record Resolution(ResultClassification semanticResult,
                             WorkflowState workflowState,
                             String paperStatus,
                             ReviewState reviewState,
                             Set<Deficit> deficits) {
        public boolean hasDeficits() {
            return !deficits.isEmpty();
        }
    }

    private PublicationStateResolver() {}

    public static Resolution resolve(ResultClassification semanticResult,
                                     WorkflowState baseWorkflowState,
                                     Set<Deficit> deficits) {
        return resolve(semanticResult, baseWorkflowState, null, deficits);
    }

    public static Resolution resolve(ResultClassification semanticResult,
                                     WorkflowState baseWorkflowState,
                                     ReviewState baseReviewState,
                                     Set<Deficit> deficits) {
        ResultClassification semantic = semanticResult == null
                ? ResultClassification.DEGENERATE : semanticResult;
        WorkflowState base = baseWorkflowState == null
                ? WorkflowState.NEEDS_REVISION : baseWorkflowState;
        EnumSet<Deficit> copied = deficits == null || deficits.isEmpty()
                ? EnumSet.noneOf(Deficit.class) : EnumSet.copyOf(deficits);
        Set<Deficit> immutableDeficits = Collections.unmodifiableSet(copied);

        // NULL_RESULT is a statement about the computation and its locked
        // hypothesis. Citation/sensitivity/replication work remains visible as
        // a revisable workflow deficit, but cannot rewrite that conclusion.
        if (semantic == ResultClassification.NULL_RESULT) {
            return new Resolution(semantic, base, "NULL_RESULT",
                    copied.isEmpty() ? (baseReviewState == null ? ReviewState.CLEAN : baseReviewState)
                            : ReviewState.REVISABLE_DEFECT,
                    immutableDeficits);
        }
        if (semantic == ResultClassification.DEGENERATE) {
            return new Resolution(semantic, WorkflowState.REJECTED, "REJECTED",
                    ReviewState.GATE_FLAGGED, immutableDeficits);
        }
        if (base == WorkflowState.REJECTED) {
            return new Resolution(semantic, base, "REJECTED", ReviewState.GATE_FLAGGED,
                    immutableDeficits);
        }
        if (base == WorkflowState.NEEDS_REVISION || !copied.isEmpty()) {
            return new Resolution(semantic, WorkflowState.NEEDS_REVISION, "NEEDS_REVISION",
                    copied.isEmpty() && baseReviewState != null
                            ? baseReviewState : ReviewState.REVISABLE_DEFECT,
                    immutableDeficits);
        }
        if (base == WorkflowState.PUBLISHED_NO_PROFESSOR) {
            return new Resolution(semantic, base, "PUBLISHED_NO_PROFESSOR",
                    ReviewState.INCOMPLETE, immutableDeficits);
        }
        return new Resolution(semantic, WorkflowState.PUBLISHED, "PUBLISHED", ReviewState.CLEAN,
                immutableDeficits);
    }
}
