package com.emergentterrain.research.literature;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
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
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.retrieval-timeout-ms:${app.research.literature.phase34-sqlite.phase35-retrieval.search-timeout-ms:45000}}")
            long retrievalTimeoutMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.dispatch-queue-timeout-ms:45000}")
            long dispatchQueueTimeoutMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.anchor-statistics-timeout-ms:5000}")
            long anchorStatisticsTimeoutMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.planning-timeout-ms:2000}")
            long planningTimeoutMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.batch-timeout-ms:10000}")
            long batchTimeoutMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.screened-merge-timeout-ms:10000}")
            long screenedMergeTimeoutMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.initial-candidates:64}")
            int initialCandidates,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.max-concurrent-primary-searches:2}")
            int maxConcurrentPrimarySearches,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.candidate-first-membership-observer-enabled:false}")
            boolean candidateFirstMembershipObserverEnabled,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.title-anchor-narrowing-enabled:false}")
            boolean titleAnchorNarrowingEnabled,
            @Value("${app.research.literature.paper-review-screened.enabled:false}")
            boolean paperReviewScreenedEnabled,
            @Value("${app.research.literature.paper-review-screened.index-dir:${user.home}/.emergentterrain/indexes/math-literature-paper-review-screened}")
            String paperReviewScreenedIndexDir,
            @Value("${app.research.literature.phase34-sqlite.shards.enabled:false}")
            boolean shardsEnabled,
            @Value("${app.research.literature.phase34-sqlite.shards.dir:${user.home}/.emergentterrain/indexes/math-literature-shards}")
            String shardsDir,
            @Value("${app.research.literature.phase34-sqlite.shards.parallelism:3}")
            int shardParallelism,
            @Value("${app.research.literature.phase34-sqlite.shards.early-success-cancellation-enabled:false}")
            boolean earlySuccessCancellationEnabled,
            ObjectProvider<CandidateFirstMembershipAuditQueue> candidateFirstMembershipAuditQueue) {
        Phase35RetrievalTimeouts timeouts = new Phase35RetrievalTimeouts(dispatchQueueTimeoutMs,
                anchorStatisticsTimeoutMs, planningTimeoutMs, retrievalTimeoutMs, batchTimeoutMs,
                screenedMergeTimeoutMs);
        PaperReviewScreenedLiterature paperReviewScreened = paperReviewScreenedEnabled
                ? new PaperReviewScreenedLiterature(Path.of(paperReviewScreenedIndexDir)) : null;
        LiteratureQueryPlanner planner = new DeterministicLiteratureQueryPlanner();
        ShardedPhase34Literature candidateShards = shardsEnabled
                ? new ShardedPhase34Literature(Path.of(shardsDir), phase35RetrievalEnabled, timeouts,
                        initialCandidates, planner, shardParallelism, earlySuccessCancellationEnabled)
                : null;
        // A half-built/missing shard set must never take the primary index offline. Keep the
        // monolith as the safe live fallback until the offline builder has produced usable files.
        ShardedPhase34Literature shardedPrimary = candidateShards != null && candidateShards.available()
                ? candidateShards : null;
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(Path.of(indexDir), phase35RetrievalEnabled, retrievalTimeoutMs,
                initialCandidates, planner, paperReviewScreened, shardedPrimary, maxConcurrentPrimarySearches,
                candidateFirstMembershipAuditQueue.getIfAvailable(), timeouts);
        adapter.setCandidateFirstMembershipObserverEnabled(candidateFirstMembershipObserverEnabled);
        adapter.setTitleAnchorNarrowingEnabled(titleAnchorNarrowingEnabled);
        return adapter;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.research.literature.phase34-sqlite.phase35-retrieval.candidate-first-membership-observer-enabled",
            havingValue = "true")
    CandidateFirstMembershipAuditQueue candidateFirstMembershipAuditQueue(
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.candidate-first-membership-audit-state-dir:${user.home}/.emergentterrain/runtime/candidate-first-membership-audits}")
            String stateDir,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.candidate-first-membership-audit-timeout-ms:10000}")
            long auditTimeoutMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.candidate-first-membership-audit-poll-interval-ms:5000}")
            long pollIntervalMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.candidate-first-membership-audit-lease-ms:30000}")
            long leaseMs,
            @Value("${app.research.literature.phase34-sqlite.phase35-retrieval.candidate-first-membership-audit-max-attempts:3}")
            int maxAttempts) {
        return new CandidateFirstMembershipAuditQueue(Path.of(stateDir), auditTimeoutMs, pollIntervalMs,
                leaseMs, maxAttempts);
    }
}
