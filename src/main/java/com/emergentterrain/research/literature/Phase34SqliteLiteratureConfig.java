package com.emergentterrain.research.literature;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Spring wiring for the Phase 34 local SQLite adapter. Disabled by default. */
@Configuration
@ConditionalOnProperty(name = "app.research.literature.phase34-sqlite.enabled", havingValue = "true")
public class Phase34SqliteLiteratureConfig {

    @Bean
    public Phase34SqliteLiterature phase34SqliteLiterature(
            @Value("${app.research.literature.phase34-sqlite.index-dir:${user.home}/.emergentterrain/indexes/math-literature}")
            String indexDir,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.enabled:false}")
            boolean phase35RetrievalEnabled,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.search-timeout-ms:15000}")
            long searchTimeoutMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.initial-candidates:64}")
            int initialCandidates,
            @Value("${app.research.literature.paper-review-screened.enabled:false}")
            boolean paperReviewScreenedEnabled,
            @Value("${app.research.literature.paper-review-screened.index-dir:${user.home}/.emergentterrain/indexes/math-literature-paper-review-screened}")
            String paperReviewScreenedIndexDir,
            @Value("${app.research.literature.phase34-sqlite.shards.enabled:false}")
            boolean shardsEnabled,
            @Value("${app.research.literature.phase34-sqlite.shards.dir:${user.home}/.emergentterrain/indexes/math-literature-shards}")
            String shardsDir,
            @Value("${app.research.literature.phase34-sqlite.shards.parallelism:3}")
            int shardParallelism) {
        PaperReviewScreenedLiterature paperReviewScreened = paperReviewScreenedEnabled
                ? new PaperReviewScreenedLiterature(Path.of(paperReviewScreenedIndexDir)) : null;
        LiteratureQueryPlanner planner = new DeterministicLiteratureQueryPlanner();
        ShardedPhase34Literature candidateShards = shardsEnabled
                ? new ShardedPhase34Literature(Path.of(shardsDir), phase35RetrievalEnabled, searchTimeoutMs,
                        initialCandidates, planner, shardParallelism)
                : null;
        // A half-built/missing shard set must never take the primary index offline. Keep the
        // monolith as the safe live fallback until the offline builder has produced usable files.
        ShardedPhase34Literature shardedPrimary = candidateShards != null && candidateShards.available()
                ? candidateShards : null;
        return new Phase34SqliteLiterature(Path.of(indexDir), phase35RetrievalEnabled, searchTimeoutMs,
                initialCandidates, planner, paperReviewScreened, shardedPrimary);
    }
}
