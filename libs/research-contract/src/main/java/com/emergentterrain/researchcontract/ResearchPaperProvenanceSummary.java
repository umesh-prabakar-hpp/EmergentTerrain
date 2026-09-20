package com.emergentterrain.researchcontract;

/**
 * Stable, read-only provenance projection for one paper.
 *
 * <p>It is intentionally a summary rather than an artifact browser: raw model traces, prompts,
 * responses, commands, filesystem locations, and arbitrary artifact files stay outside the v1
 * API.</p>
 */
public record ResearchPaperProvenanceSummary(
        String paperId,
        ResearchArtifactIntegritySummary artifact,
        ResearchStudyProvenanceSummary study,
        ResearchEvidenceProvenanceSummary evidence) {

    public static ResearchPaperProvenanceSummary pending(String paperId) {
        return new ResearchPaperProvenanceSummary(paperId,
                new ResearchArtifactIntegritySummary(false, "PENDING", null, null,
                        null, null, null, 0),
                new ResearchStudyProvenanceSummary(false, false, false, false, false, false),
                new ResearchEvidenceProvenanceSummary(false, false, 0,
                        new ResearchLiteratureEvidenceSummary(false, 0, 0, 0, 0, false)));
    }

    public static ResearchPaperProvenanceSummary missing(String paperId) {
        ResearchPaperProvenanceSummary pending = pending(paperId);
        return new ResearchPaperProvenanceSummary(paperId,
                new ResearchArtifactIntegritySummary(false, "MISSING", null, null,
                        null, null, null, 0), pending.study(), pending.evidence());
    }
}
