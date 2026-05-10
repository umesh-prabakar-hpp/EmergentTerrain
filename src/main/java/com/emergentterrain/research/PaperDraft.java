package com.emergentterrain.research;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Holds the completed peer-review result and all metadata needed to publish a paper,
 * pending LLM narration. Reputation and papersSinceTerm are updated at draft-creation
 * time so evolutionary pressure is not delayed by LLM latency.
 *
 * recentPapers is a snapshot of the room's published papers at draft time — used as
 * context for the narration prompt. rawFindings / templateHypothesis / templateConclusion
 * are used verbatim if the LLM call fails.
 */
public record PaperDraft(
        UUID   roomId,
        String authorBotId,
        String domain,
        String topicName,
        String title,
        String rawFindings,
        String templateHypothesis,
        String templateConclusion,
        String methodology,
        List<String> reviewerBotIds,
        double peerScore,
        double authorScore,
        double anomalyZScore,
        long   gameTick,
        Instant publishedAt,
        int    generation,
        String paperId,
        Integer param1,
        Double  param2,
        boolean isFollowUp,
        Double  predictedScore,
        String  priorMethodology,
        List<ResearchPaper> recentPapers,
        String  researchHypothesis,
        PreRegistration preRegistration,
        ResultClassification resultClassification,
        /**
         * Phase 2b — the {@link ProgramOpenQuestion} ID that
         * {@link ExperimentPlannerService} targeted when picking this draft's
         * params, or null when the planner wasn't consulted. After the paper
         * publishes, the publish path calls {@code planner.recordOutcome} so
         * the question's uncertainty decays automatically.
         */
        UUID plannerQuestionId
) {}
