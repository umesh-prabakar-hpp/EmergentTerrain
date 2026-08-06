package com.emergentterrain.researchcontract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Frozen execution classification for a selected draft; non-standard kinds remain deferred. */
public record ResearchDraftExecutionIntent(String kind, JsonNode payload) {
    public static final String STANDARD_CATALOG = "STANDARD_CATALOG";
    public static final String DEFERRED_PARAMETERIZED_STUDY = "DEFERRED_PARAMETERIZED_STUDY";
    public static final String DEFERRED_FOLLOW_UP_STUDY = "DEFERRED_FOLLOW_UP_STUDY";

    public ResearchDraftExecutionIntent {
        if (!STANDARD_CATALOG.equals(kind) && !DEFERRED_PARAMETERIZED_STUDY.equals(kind)
                && !DEFERRED_FOLLOW_UP_STUDY.equals(kind)) {
            throw new IllegalArgumentException("Unsupported draft execution kind");
        }
        payload = payload == null ? JsonNodeFactory.instance.objectNode() : payload;
    }

    public static ResearchDraftExecutionIntent standardCatalog() {
        return new ResearchDraftExecutionIntent(STANDARD_CATALOG, JsonNodeFactory.instance.objectNode());
    }
}
