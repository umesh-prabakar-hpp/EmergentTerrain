package com.emergentterrain.researchservice;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Bounded worker for generic research work; service restarts resume from durable queue state. */
@Service
public class ResearchWorkScheduler {
    private final ResearchWorkItemRepository workItems;
    private final ResearchWorkExecutionService execution;

    public ResearchWorkScheduler(ResearchWorkItemRepository workItems, ResearchWorkExecutionService execution) {
        this.workItems = workItems;
        this.execution = execution;
    }

    @Scheduled(fixedDelayString = "${app.research-service.work-interval-ms:1000}")
    public void runQueuedWork() {
        workItems.findTop20ByStateOrderByAcceptedAtAsc("QUEUED").forEach(execution::execute);
    }
}
