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
}
