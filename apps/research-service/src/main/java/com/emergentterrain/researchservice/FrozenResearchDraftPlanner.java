package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchDraftPlan;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/** Deterministic generic draft-candidate planner; legacy-equivalence routing remains disabled. */
@Service
public class FrozenResearchDraftPlanner {
    private final ResearchDraftPlanRepository plans;
    public FrozenResearchDraftPlanner(ResearchDraftPlanRepository plans) { this.plans = plans; }

    @Transactional
    public ResearchDraftPlan plan(ResearchWorkRequest request) {
        ResearchSubject subject = request.subjects().stream().min(Comparator.comparing(ResearchSubject::subjectId))
                .orElseThrow(() -> new IllegalArgumentException("A draft plan requires at least one subject"));
        String domain = text(subject, "domain", text(subject, "classification", "GENERAL"));
        String topic = text(subject, "topic", "frozen-baseline-" + request.sourceRevision());
        UUID draftId = UUID.nameUUIDFromBytes(("draft-plan:" + request.requestId()).getBytes(StandardCharsets.UTF_8));
        ResearchDraftPlan plan = new ResearchDraftPlan(draftId, request.requestId(), request.workspaceId(),
                subject.subjectId(), domain, topic, "CANDIDATE", Instant.now());
        if (!plans.existsById(draftId)) {
            plans.save(new ResearchDraftPlanRecord(plan.draftId(), plan.requestId(), plan.workspaceId(),
                    plan.subjectId(), plan.domain(), plan.topic(), plan.eligibility(), plan.plannedAt()));
        }
        return plan;
    }

    private static String text(ResearchSubject subject, String field, String fallback) {
        String value = subject.attributes().path(field).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }
}
