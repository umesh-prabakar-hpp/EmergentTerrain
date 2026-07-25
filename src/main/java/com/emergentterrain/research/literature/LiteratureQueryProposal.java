package com.emergentterrain.research.literature;

import java.util.List;

/**
 * Semantic query intent proposed by the research model before a Phase 35 lookup.
 *
 * <p>The proposal is not executable input. {@link LiteratureQueryPlanner} must validate its
 * anchors against the grounded claim and turn them into a bounded {@link LiteratureQueryPlan}
 * before the SQLite adapter may query or issue an {@code HF...} key.
 */
public record LiteratureQueryProposal(
        List<String> subjectAnchors,
        String claimedRelation,
        String relevanceRationale) {

    public LiteratureQueryProposal {
        subjectAnchors = subjectAnchors == null ? List.of() : List.copyOf(subjectAnchors);
        claimedRelation = claimedRelation == null ? "" : claimedRelation;
        relevanceRationale = relevanceRationale == null ? "" : relevanceRationale;
    }
}
