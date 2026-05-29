package com.emergentterrain.research;

import java.util.List;
import java.util.UUID;

/**
 * 2026-05-29 — INCOMPLETE re-queue: a serialization-safe snapshot of the
 * re-narration-relevant fields of a {@link PaperDraft}, persisted as JSON on the
 * published paper ({@code rework_draft_json}) when its {@link ReviewState} is
 * {@link ReviewState#INCOMPLETE}, so the rework sweep can re-narrate the paper
 * faithfully from its true seed inputs (raw findings, sweep params,
 * pre-registration) rather than from the already-narrated prose.
 *
 * <p>Deliberately a narrow subset of {@link PaperDraft}: the entity-bearing
 * fields ({@code recentPapers} — a {@code List<ResearchPaper>}) and the
 * one-shot bookkeeping fields ({@code plannerQuestionId}, scores, tick) are
 * dropped because they would make the JSON fragile/heavy and are not needed to
 * re-run the narration. {@link #toDraft()} rehydrates a {@link PaperDraft} with
 * those defaulted (empty {@code recentPapers}, null {@code plannerQuestionId}).
 *
 * <p>The {@code paperId} is preserved so the re-narration's
 * {@code publishPaper} upserts the SAME row (in-place finish), rather than
 * spawning a duplicate paper.
 */
public record ReworkDraftSnapshot(
        UUID roomId,
        String authorBotId,
        String domain,
        String topicName,
        String title,
        String rawFindings,
        String templateHypothesis,
        String templateConclusion,
        String methodology,
        int generation,
        String paperId,
        Integer param1,
        Double param2,
        String researchHypothesis,
        PreRegistration preRegistration,
        ResultClassification resultClassification,
        PaperKind kind) {

    /** Capture the re-narration-relevant fields of {@code draft}. */
    public static ReworkDraftSnapshot from(PaperDraft draft) {
        return new ReworkDraftSnapshot(
                draft.roomId(),
                draft.authorBotId(),
                draft.domain(),
                draft.topicName(),
                draft.title(),
                draft.rawFindings(),
                draft.templateHypothesis(),
                draft.templateConclusion(),
                draft.methodology(),
                draft.generation(),
                draft.paperId(),
                draft.param1(),
                draft.param2(),
                draft.researchHypothesis(),
                draft.preRegistration(),
                draft.resultClassification(),
                draft.kind());
    }

    /**
     * Rehydrate a {@link PaperDraft} for re-narration. Reuses the original
     * {@code paperId} (so the re-publish upserts the same row), and defaults the
     * dropped fields: empty {@code reviewerBotIds}/{@code recentPapers}, zeroed
     * scores/tick, null {@code publishedAt}/{@code predictedScore}/
     * {@code priorMethodology}/{@code plannerQuestionId}, {@code isFollowUp=false}.
     */
    public PaperDraft toDraft() {
        return new PaperDraft(
                roomId,
                authorBotId,
                domain,
                topicName,
                title,
                rawFindings,
                templateHypothesis,
                templateConclusion,
                methodology,
                List.of(),                 // reviewerBotIds — reassigned by the panel
                0.0,                       // peerScore
                0.0,                       // authorScore
                0.0,                       // anomalyZScore
                0L,                        // gameTick
                null,                      // publishedAt — stamped at publish
                generation,
                paperId,                   // SAME id → in-place upsert
                param1,
                param2,
                false,                     // isFollowUp
                null,                      // predictedScore
                null,                      // priorMethodology
                List.of(),                 // recentPapers — novelty context, optional
                researchHypothesis,
                preRegistration,
                resultClassification,
                null,                      // plannerQuestionId — outcome already recorded by the original
                kind);
    }
}
