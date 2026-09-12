package com.emergentterrain.research.quality;

import com.emergentterrain.repo.ResearchPaperRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Operator-enabled, bounded legacy assessment backfill.
 *
 * <p>It selects only outputs without any assessment record. Existing AI assessments are immutable
 * history and are never silently rerun or replaced by a startup job.
 */
@Component
@ConditionalOnProperty(name = "app.research.quality-adjudication.backfill.enabled", havingValue = "true")
@ConditionalOnBean(AiResearchQualityAdjudicator.class)
public class ResearchQualityBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(ResearchQualityBackfillRunner.class);

    private final ResearchPaperRepository papers;
    private final AiResearchQualityAdjudicator adjudicator;
    private final int maxPapers;

    public ResearchQualityBackfillRunner(
            ResearchPaperRepository papers,
            AiResearchQualityAdjudicator adjudicator,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.research.quality-adjudication.backfill.max-papers:1}")
            int maxPapers) {
        this.papers = papers;
        this.adjudicator = adjudicator;
        this.maxPapers = Math.max(1, Math.min(25, maxPapers));
    }

    @org.springframework.context.annotation.Bean
    ApplicationRunner phase43QualityBackfill() {
        return args -> {
            var candidates = papers.findUnassessedForQualityBackfill(PageRequest.of(0, maxPapers));
            log.info("Phase 43 quality backfill selected {} unassessed output(s), max={}",
                    candidates.size(), maxPapers);
            for (var paper : candidates) {
                adjudicator.adjudicateHistorical(paper);
            }
        };
    }
}
