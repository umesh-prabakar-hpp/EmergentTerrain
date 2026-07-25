package com.emergentterrain.research.literature;

import java.util.Map;

/**
 * Produces validated, retrieval-safe FTS plans for a literature claim.
 *
 * <p>The current implementation is deterministic. A future model-assisted tool may suggest
 * anchors, but it must produce the same {@link LiteratureQueryPlan} contract and cannot bypass
 * its expression or required-title-anchor validation.
 */
interface LiteratureQueryPlanner {

    LiteratureQueryPlan plan(String claim, Map<String, Integer> documentCounts);

    /**
     * Accepts a model-proposed semantic boundary only after the planner has validated it. The
     * default keeps existing planners and callers byte-compatible.
     */
    default LiteratureQueryPlan plan(String claim, Map<String, Integer> documentCounts,
                                    LiteratureQueryProposal proposal) {
        return plan(claim, documentCounts);
    }
}
