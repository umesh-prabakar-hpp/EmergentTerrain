package com.emergentterrain.research.publication;

/** Reader-facing publication tier, intentionally separate from workflow status. */
public enum PublicationLabel {
    /** A retained pipeline result; useful for exploration, not a paper claim. */
    INTERNAL_RESEARCH_NOTE,
    /** Has a locked claim and a computational trace, but lacks one or more paper gates. */
    COMPUTATIONAL_RESEARCH_NOTE,
    /** Has the required plan, artifact bundle, and literature positioning; still a candidate for human review. */
    ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE;

    public String displayName() {
        return switch (this) {
            case INTERNAL_RESEARCH_NOTE -> "Internal research note";
            case COMPUTATIONAL_RESEARCH_NOTE -> "Computational research note";
            case ARTIFACT_BACKED_COMPUTATIONAL_PAPER_CANDIDATE ->
                    "Artifact-backed computational paper candidate";
        };
    }
}
