package com.emergentterrain.researchcontract;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable, source-agnostic candidate supplied to a draft-selection request. */
public record ResearchDraftCandidate(UUID subjectId, String tier, double selectionScore, String domain,
                                     List<ResearchTopicOption> topicOptions,
                                     ResearchParameterSamplingProfile parameterSamplingProfile) {
    public ResearchDraftCandidate {
        Objects.requireNonNull(subjectId, "subjectId");
        if (!"PRIMARY".equals(tier) && !"FALLBACK".equals(tier)) {
            throw new IllegalArgumentException("tier must be PRIMARY or FALLBACK");
        }
        if (!Double.isFinite(selectionScore)) throw new IllegalArgumentException("selectionScore must be finite");
        if (domain == null || domain.isBlank()) throw new IllegalArgumentException("domain is required");
        topicOptions = topicOptions == null ? List.of() : List.copyOf(topicOptions);
        if (topicOptions.isEmpty()) throw new IllegalArgumentException("topicOptions are required");
    }

    /** Compatibility constructor for frozen inputs created before fallback sampling was captured. */
    public ResearchDraftCandidate(UUID subjectId, String tier, double selectionScore, String domain,
                                  List<ResearchTopicOption> topicOptions) {
        this(subjectId, tier, selectionScore, domain, topicOptions, null);
    }
}
