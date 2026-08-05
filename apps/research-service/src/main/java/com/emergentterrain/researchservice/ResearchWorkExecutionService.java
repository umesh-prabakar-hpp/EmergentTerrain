package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchResult;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Claims queued work, runs one supported generic processor, and retains its durable result event. */
@Service
public class ResearchWorkExecutionService {
    private final ResearchWorkItemRepository workItems;
    private final List<ResearchWorkExecutor> executors;
    private final ObjectMapper objectMapper;

    public ResearchWorkExecutionService(ResearchWorkItemRepository workItems, List<ResearchWorkExecutor> executors,
                                        ObjectMapper objectMapper) {
        this.workItems = workItems;
        this.executors = executors;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void execute(ResearchWorkItem item) {
        if (!"QUEUED".equals(item.getState())) return;
        item.markRunning();
        try {
            ResearchWorkRequest request = objectMapper.readValue(item.getRequestJson(), ResearchWorkRequest.class);
            ResearchWorkExecutor executor = executors.stream().filter(candidate -> candidate.supports(request.workType()))
                    .findFirst().orElse(null);
            if (executor == null) {
                item.markFailed("No generic executor supports " + request.workType());
            } else {
                ResearchResult result = executor.execute(request);
                item.markCompleted(objectMapper.writeValueAsString(result));
            }
        } catch (Exception failure) {
            if (item.getAttempts() >= 3) item.markFailed(failure.getClass().getSimpleName());
            else item.markDeferred(failure.getClass().getSimpleName());
        }
        workItems.save(item);
    }
}
