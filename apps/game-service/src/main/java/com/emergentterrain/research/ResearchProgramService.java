package com.emergentterrain.research;

import com.emergentterrain.repo.ProgramFailedApproachRepository;
import com.emergentterrain.repo.ProgramOpenQuestionRepository;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.repo.ResearchProgramRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 1 Iter 4 — manages {@link ResearchProgram} lifecycle:
 *  - find or create the active program for a (bot, domain) pair on each publish
 *  - attach the published paper to the program (FK on ResearchPaper.programId)
 *  - update evidenceFor / evidenceAgainst from the paper's status + score
 *  - record failed approaches when peer review or replication rejects a paper
 *  - record open questions from the LLM's follow-up suggestion
 *
 * Gated by {@code app.research.programs.enabled} (default false). When the flag
 * is off, every method becomes a no-op so the existing pendingFollowUp /
 * researchHypothesis flow stays in charge.
 *
 * The service does not touch BotLifecycleState — coexistence with the legacy
 * follow-up path is intentional during the rollout window.
 */
@Service
public class ResearchProgramService {

    private static final Logger log = LoggerFactory.getLogger(ResearchProgramService.class);

    /**
     * Evidence threshold at which an ACTIVE program closes as CONVERGED. The
     * sign of (evidenceFor - evidenceAgainst) determines whether convergence is
     * a positive consensus or a refuted hypothesis (caller can interpret).
     */
    private static final double CONVERGENCE_THRESHOLD = 5.0;

    private final boolean enabled;
    private final ResearchProgramRepository programs;
    private final ProgramOpenQuestionRepository openQuestions;
    private final ProgramFailedApproachRepository failedApproaches;
    private final ResearchPaperRepository papers;

    public ResearchProgramService(
            @Value("${app.research.programs.enabled:false}") boolean enabled,
            ResearchProgramRepository programs,
            ProgramOpenQuestionRepository openQuestions,
            ProgramFailedApproachRepository failedApproaches,
            ResearchPaperRepository papers) {
        this.enabled          = enabled;
        this.programs         = programs;
        this.openQuestions    = openQuestions;
        this.failedApproaches = failedApproaches;
        this.papers           = papers;
        if (enabled) {
            log.info("ResearchProgramService: ENABLED — programs will be created and "
                    + "papers attached. Existing pendingFollowUp flow remains in charge.");
        }
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Called from the publish path. Finds-or-creates the program for the paper's
     * (author, domain), attaches the paper to it, and updates evidence counters.
     * No-op when the feature flag is off.
     */
    public void onPaperPublished(ResearchPaper paper, ResultClassification classification,
                                  String firstHypothesis) {
        if (!enabled || paper == null) return;
        try {
            ResearchProgram program = findOrCreateProgram(paper, firstHypothesis);
            paper.setProgramId(program.getId());
            try {
                papers.save(paper);
            } catch (Exception e) {
                log.warn("program: failed to persist paper.programId paperId={}: {}",
                        paper.paperId(), e.getMessage());
            }

            program.setPaperCount(program.getPaperCount() + 1);
            program.setLastPaperAt(Instant.now());
            switch (classification) {
                case SUPPORTED          -> program.setEvidenceFor(program.getEvidenceFor() + paper.peerScore());
                case PARTIALLY_SUPPORTED -> program.setEvidenceFor(program.getEvidenceFor() + paper.peerScore() * 0.5);
                case NULL_RESULT        -> program.setEvidenceAgainst(program.getEvidenceAgainst() + 0.5);
                case DEGENERATE         -> { /* should never reach publish; defensive */ }
            }

            // Convergence: close the program when evidence settles.
            double margin = Math.abs(program.getEvidenceFor() - program.getEvidenceAgainst());
            if (margin >= CONVERGENCE_THRESHOLD && program.getStatus() == ResearchProgram.Status.ACTIVE) {
                program.setStatus(ResearchProgram.Status.CONVERGED);
                log.info("program: CONVERGED programId={} title=\"{}\" evidenceFor={} evidenceAgainst={}",
                        program.getId(), program.getTitle(),
                        program.getEvidenceFor(), program.getEvidenceAgainst());
            }
            programs.save(program);
        } catch (Exception e) {
            log.warn("program: onPaperPublished failed paperId={}: {}",
                    paper != null ? paper.paperId() : "null", e.getMessage());
        }
    }

    /**
     * Called when a paper is rejected (degenerate, failed peer review, or marked
     * DISPUTED post-replication). Records the failed approach against the bot's
     * current active program for this domain, if any.
     */
    public void recordFailedApproach(UUID ownerBotId, String domain, String approach,
                                     String whyFailed, String associatedPaperId) {
        if (!enabled) return;
        try {
            programs.findFirstByOwnerBotIdAndDomainAndStatus(
                    ownerBotId, domain, ResearchProgram.Status.ACTIVE)
                .ifPresent(program -> failedApproaches.save(new ProgramFailedApproach(
                        program.getId(), approach, whyFailed,
                        associatedPaperId, Instant.now())));
        } catch (Exception e) {
            log.warn("program: recordFailedApproach failed bot={} domain={}: {}",
                    ownerBotId, domain, e.getMessage());
        }
    }

    /**
     * Called when the LLM suggests a follow-up hypothesis as part of narration.
     * The follow-up becomes a new open question on the program.
     */
    public void recordOpenQuestion(UUID ownerBotId, String domain, String question) {
        if (!enabled || question == null || question.isBlank()) return;
        try {
            programs.findFirstByOwnerBotIdAndDomainAndStatus(
                    ownerBotId, domain, ResearchProgram.Status.ACTIVE)
                .ifPresent(program -> openQuestions.save(new ProgramOpenQuestion(
                        program.getId(), question, Instant.now())));
        } catch (Exception e) {
            log.warn("program: recordOpenQuestion failed bot={} domain={}: {}",
                    ownerBotId, domain, e.getMessage());
        }
    }

    private ResearchProgram findOrCreateProgram(ResearchPaper paper, String firstHypothesis) {
        UUID ownerBotId = UUID.fromString(paper.authorBotId());
        Optional<ResearchProgram> existing = programs.findFirstByOwnerBotIdAndDomainAndStatus(
                ownerBotId, paper.domain(), ResearchProgram.Status.ACTIVE);
        if (existing.isPresent()) return existing.get();

        // Title falls back to the paper's domain+topic if the LLM didn't supply one.
        // Phase 4 will replace this with an LLM-generated program title prompt.
        String title = "Program: " + paper.domain() + " / " + paper.topic();
        String objective = (firstHypothesis != null && !firstHypothesis.isBlank())
                ? firstHypothesis
                : paper.hypothesis();

        ResearchProgram program = new ResearchProgram(
                ownerBotId, paper.roomId(), paper.domain(), title, objective, Instant.now());
        ResearchProgram saved = programs.save(program);
        log.info("program: NEW programId={} bot={} domain={} title=\"{}\"",
                saved.getId(), ownerBotId, paper.domain(), title);
        return saved;
    }
}
