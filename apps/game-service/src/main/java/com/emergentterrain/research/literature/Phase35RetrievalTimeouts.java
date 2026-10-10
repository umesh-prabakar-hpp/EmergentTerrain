package com.emergentterrain.research.literature;

/**
 * Independent Phase 35 time limits.  They deliberately do not form an end-to-end request
 * deadline: a slow metadata lookup must not steal the retrieval stage's evidence budget.
 */
record Phase35RetrievalTimeouts(long dispatchQueueTimeoutMs, long anchorStatisticsTimeoutMs,
                                long planningTimeoutMs, long retrievalTimeoutMs,
                                long batchTimeoutMs, long screenedMergeTimeoutMs) {
    static final long DEFAULT_DISPATCH_QUEUE_TIMEOUT_MS = 45_000;
    static final long DEFAULT_ANCHOR_STATISTICS_TIMEOUT_MS = 5_000;
    static final long DEFAULT_PLANNING_TIMEOUT_MS = 2_000;
    static final long DEFAULT_RETRIEVAL_TIMEOUT_MS = 45_000;
    static final long DEFAULT_BATCH_TIMEOUT_MS = 10_000;
    static final long DEFAULT_SCREENED_MERGE_TIMEOUT_MS = 10_000;

    Phase35RetrievalTimeouts {
        dispatchQueueTimeoutMs = bounded(dispatchQueueTimeoutMs, 250, 120_000);
        anchorStatisticsTimeoutMs = bounded(anchorStatisticsTimeoutMs, 250, 60_000);
        planningTimeoutMs = bounded(planningTimeoutMs, 50, 30_000);
        retrievalTimeoutMs = bounded(retrievalTimeoutMs, 1_000, 300_000);
        batchTimeoutMs = bounded(batchTimeoutMs, 250, retrievalTimeoutMs);
        screenedMergeTimeoutMs = bounded(screenedMergeTimeoutMs, 250, 60_000);
    }

    static Phase35RetrievalTimeouts defaults() {
        return new Phase35RetrievalTimeouts(DEFAULT_DISPATCH_QUEUE_TIMEOUT_MS,
                DEFAULT_ANCHOR_STATISTICS_TIMEOUT_MS, DEFAULT_PLANNING_TIMEOUT_MS,
                DEFAULT_RETRIEVAL_TIMEOUT_MS, DEFAULT_BATCH_TIMEOUT_MS,
                DEFAULT_SCREENED_MERGE_TIMEOUT_MS);
    }

    /** Retains the former single-budget behavior for direct constructor callers and focused tests. */
    static Phase35RetrievalTimeouts legacy(long timeoutMs) {
        long bounded = Math.max(1_000, timeoutMs);
        return new Phase35RetrievalTimeouts(bounded, bounded, bounded, bounded,
                Math.min(DEFAULT_BATCH_TIMEOUT_MS, bounded), bounded);
    }

    private static long bounded(long value, long minimum, long maximum) {
        return Math.clamp(value, minimum, maximum);
    }
}
