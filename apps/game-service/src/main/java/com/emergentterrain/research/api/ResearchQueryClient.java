package com.emergentterrain.research.api;

import com.emergentterrain.researchcontract.ResearchPaperSummary;
import com.emergentterrain.researchcontract.ResearchQualityAssessmentSummary;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read boundary from gameplay to research.
 *
 * <p>The local implementation maintains compatibility during extraction.  The HTTP implementation
 * uses the same v1 resource contract when research is deployed separately.</p>
 */
public interface ResearchQueryClient {

    List<ResearchPaperSummary> getPublishedPapers(UUID roomId, int limit);

    List<ResearchQualityAssessmentSummary> getQualityAssessmentHistory(UUID roomId, String paperId);

    Map<String, Object> getStats(UUID roomId);
}
