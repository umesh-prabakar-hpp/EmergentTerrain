package com.emergentterrain.research;

import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.service.ResearchNarrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 2026-05-29 — automated re-queue for {@code (NEEDS_REVISION, INCOMPLETE)} papers.
 *
 * <p>A paper published {@code NEEDS_REVISION} with {@link ReviewState#INCOMPLETE}
 * is sound-but-unfinished — a rec #8 wall-clock-timeout first-pass salvage, a
 * token-truncated draft, a missing-Methods downgrade, a degraded review panel, or
 * a no-professor fallback. The content may be entirely correct; the pipeline just
 * did not finish. Unlike {@code REVISABLE_DEFECT} (a real content fix a human must
 * make) or {@code GATE_FLAGGED} (a validator deliberately blocked it), these
 * deserve one more automated pass — especially now the narration-timeout interrupt
 * is actually enforced (rec #2), so a re-run is far more likely to complete.
 *
 * <p>This sweep finds those papers (oldest first, batch-capped), rehydrates the
 * {@link ReworkDraftSnapshot} persisted on each at publish time, and re-enqueues
 * it through {@link ResearchNarrationService#enqueueNarration}. The rehydrated
 * draft reuses the SAME {@code paperId}, so the re-narration's {@code publishPaper}
 * upserts the same row (an in-place finish, not a duplicate paper).
 *
 * <h2>Loop cap</h2>
 * Each candidate's {@code reworkAttempts} is incremented (and its snapshot
 * consumed) <em>before</em> the async re-narration is dispatched, so it is
 * immediately excluded from the next tick's candidate query. {@code publishPaper}
 * carries the bumped count forward across the in-place re-publish, so a rework
 * that comes out INCOMPLETE again lands at {@code maxAttempts} and is never
 * re-queued. With the default {@code max-attempts=1}, every paper is finished at
 * most once; the original content is left untouched if the re-narration fails.
 *
 * <p>Shipped dormant behind {@code app.research.incomplete-rework.enabled}
 * (default false), matching the rollout discipline of the other behaviour-changing
 * gates — flip after a refresh confirms the re-narrated papers are worth the spend.
 */
@Component
public class IncompleteReworkService {

    private static final Logger log = LoggerFactory.getLogger(IncompleteReworkService.class);

    private final ResearchPaperRepository papersRepository;
    private final ResearchNarrationService narrationService;
    private final ObjectMapper objectMapper;

    private final boolean enabled;
    private final int maxAttempts;
    private final int batchSize;

    public IncompleteReworkService(
            ResearchPaperRepository papersRepository,
            ResearchNarrationService narrationService,
            ObjectMapper objectMapper,
            @Value("${app.research.incomplete-rework.enabled:false}") boolean enabled,
            @Value("${app.research.incomplete-rework.max-attempts:1}") int maxAttempts,
            @Value("${app.research.incomplete-rework.batch-size:5}") int batchSize) {
        this.papersRepository = papersRepository;
        this.narrationService = narrationService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * Periodic sweep. Single-threaded by Spring's default {@code @Scheduled} pool,
     * so ticks never overlap. No-op when the flag is off.
     */
    @Scheduled(fixedDelayString = "${app.research.incomplete-rework.poll-interval-ms:60000}",
            initialDelayString = "${app.research.incomplete-rework.initial-delay-ms:60000}")
    public void tick() {
        if (!enabled) return;
        List<ResearchPaper> candidates;
        try {
            candidates = papersRepository.findReworkCandidates(
                    maxAttempts, PageRequest.of(0, batchSize));
        } catch (Exception e) {
            log.warn("incomplete-rework: candidate query failed: {}", e.toString());
            return;
        }
        if (candidates.isEmpty()) return;
        int dispatched = 0;
        for (ResearchPaper paper : candidates) {
            if (requeue(paper)) dispatched++;
        }
        if (dispatched > 0) {
            log.info("incomplete-rework: dispatched {} re-narration(s) of {} candidate(s) "
                    + "(maxAttempts={})", dispatched, candidates.size(), maxAttempts);
        }
    }

    /**
     * Re-narrate one INCOMPLETE paper. Bumps the attempt count and consumes the
     * snapshot first (so a concurrent/next tick can't re-pick it), then dispatches
     * the async re-narration. Returns true when a re-narration was dispatched.
     */
    private boolean requeue(ResearchPaper paper) {
        PaperDraft draft;
        try {
            ReworkDraftSnapshot snap =
                    objectMapper.readValue(paper.reworkDraftJson(), ReworkDraftSnapshot.class);
            draft = snap.toDraft();
        } catch (Exception e) {
            // Corrupt/unreadable snapshot — consume it so we don't retry forever.
            log.warn("incomplete-rework: snapshot rehydrate failed paperId={}, consuming: {}",
                    paper.paperId(), e.toString());
            paper.setReworkAttempts(maxAttempts);
            paper.setReworkDraftJson(null);
            saveQuietly(paper);
            return false;
        }
        // Bump + consume BEFORE dispatch so this paper is excluded from the next
        // tick immediately; publishPaper carries the bumped count forward across
        // the in-place re-publish, capping the chain.
        paper.setReworkAttempts(paper.reworkAttempts() + 1);
        paper.setReworkDraftJson(null);
        if (!saveQuietly(paper)) return false;   // don't dispatch if we couldn't record the bump
        log.info("incomplete-rework: re-narrating paperId={} attempt={}/{}",
                paper.paperId(), paper.reworkAttempts(), maxAttempts);
        try {
            narrationService.enqueueNarration(draft);
            return true;
        } catch (Exception e) {
            log.warn("incomplete-rework: enqueue failed paperId={}: {}",
                    paper.paperId(), e.toString());
            return false;
        }
    }

    private boolean saveQuietly(ResearchPaper paper) {
        try {
            papersRepository.save(paper);
            return true;
        } catch (Exception e) {
            log.warn("incomplete-rework: save failed paperId={}: {}", paper.paperId(), e.toString());
            return false;
        }
    }
}
