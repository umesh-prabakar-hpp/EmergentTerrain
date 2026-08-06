package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.researchcontract.ResearchDraftCandidate;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftSelection;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchParameterOverride;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchStudySpecification;
import com.emergentterrain.researchcontract.ResearchTopicOption;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Game-side anti-corruption adapter; no game types cross the research contract. */
@Service
public class GameResearchWorkMapper {

    private final ObjectMapper objectMapper;
    private final boolean topicsV2Enabled;

    public GameResearchWorkMapper(ObjectMapper objectMapper,
                                  @Value("${app.research.topics-v2.enabled:false}") boolean topicsV2Enabled) {
        this.objectMapper = objectMapper;
        this.topicsV2Enabled = topicsV2Enabled;
    }

    public ResearchWorkRequest fromTick(UUID workspaceId, List<RoomBot> activeBots, long sourceRevision,
                                        GovernmentState governmentState) {
        List<ResearchSubject> subjects = activeBots.stream()
                .map(this::subjectFor)
                .toList();
        ObjectNode context = objectMapper.createObjectNode();
        context.set("governance", objectMapper.valueToTree(governmentState));
        context.put("subjectCount", subjects.size());
        context.set("draftSelection", objectMapper.valueToTree(
                standardSelectionInput(workspaceId, activeBots, sourceRevision)));
        UUID requestId = UUID.nameUUIDFromBytes(("research-cycle:" + workspaceId + ":" + sourceRevision)
                .getBytes(StandardCharsets.UTF_8));
        return new ResearchWorkRequest(requestId, workspaceId, "RESEARCH_CYCLE", sourceRevision,
                Instant.now(), subjects, context);
    }

    private ResearchSubject subjectFor(RoomBot bot) {
        ObjectNode attributes = objectMapper.createObjectNode();
        attributes.put("classification", bot.getType());
        attributes.set("profile", readJson(bot.getProfileJson()));
        attributes.set("state", readJson(bot.getStateJson()));
        attributes.set("lifecycle", readJson(bot.getLifecycleJson()));
        return new ResearchSubject(bot.getId(), "AGENT", attributes);
    }

    /**
     * Freezes the legacy standard-catalog selection inputs into source-agnostic fields. Dynamic,
     * conjecture, follow-up, and history-dependent branches remain outside this first parity slice.
     */
    private ResearchDraftSelectionInput standardSelectionInput(UUID workspaceId, List<RoomBot> activeBots,
                                                                long sourceRevision) {
        List<SelectionSource> mature = new ArrayList<>();
        List<SelectionSource> qualified = new ArrayList<>();
        for (RoomBot bot : activeBots) {
            BotLifecycleState lifecycle = lifecycle(bot);
            if (lifecycle == null || lifecycle.getGenome() == null
                    || lifecycle.getLifecycle() == BotLifecycleState.Phase.JUVENILE) continue;
            SelectionSource source = new SelectionSource(bot.getId(), lifecycle);
            mature.add(source);
            if (lifecycle.getResearchStatus() == BotLifecycleState.ResearchStatus.QUALIFIED) qualified.add(source);
        }
        boolean useFallback = qualified.isEmpty();
        List<SelectionSource> pool = useFallback ? mature : qualified;
        List<ResearchDraftCandidate> candidates = pool.stream()
                .map(source -> candidate(source, useFallback ? "FALLBACK" : "PRIMARY"))
                .toList();
        String topicMode = topicsV2Enabled ? "UNIFORM" : "WEIGHTED";
        long seed = sourceRevision * 1337L + workspaceId.getLeastSignificantBits();
        ResearchDraftSelectionInput standardInput = new ResearchDraftSelectionInput(
                "legacy-standard-catalog-v1", seed, topicMode, candidates);
        if (candidates.isEmpty()) return standardInput;
        ResearchDraftSelection selection = com.emergentterrain.researchcontract.StandardResearchDraftSelector
                .select(standardInput);
        SelectionSource selected = pool.stream().filter(source -> source.subjectId().equals(selection.subjectId()))
                .findFirst().orElseThrow();
        return new ResearchDraftSelectionInput(standardInput.selectorVersion(), standardInput.selectionSeed(),
                standardInput.topicSelectionMode(), standardInput.candidates(), executionIntent(selected.lifecycle()));
    }

    private ResearchDraftCandidate candidate(SelectionSource source, String tier) {
        String domain = source.lifecycle().getGenome().getDomain();
        int count = topicsV2Enabled ? MathPhysicsEngine.topicCount(domain)
                : Math.min(5, source.lifecycle().getGenome().getTopicWeights().length);
        List<ResearchTopicOption> topics = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double weight = topicsV2Enabled ? 1.0 : source.lifecycle().getGenome().getTopicWeights()[index];
            topics.add(new ResearchTopicOption(MathPhysicsEngine.topicName(domain, index), weight));
        }
        return new ResearchDraftCandidate(source.subjectId(), tier, source.lifecycle().fitness(), domain, topics);
    }

    private BotLifecycleState lifecycle(RoomBot bot) {
        String json = bot.getLifecycleJson();
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, BotLifecycleState.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ResearchDraftExecutionIntent executionIntent(BotLifecycleState lifecycle) {
        if (lifecycle.getPendingDirective() != null) {
            var directive = lifecycle.getPendingDirective();
            return new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY,
                    objectMapper.valueToTree(new ResearchStudySpecification(
                            ResearchStudySpecification.LEGACY_PARAMETERIZED_STUDY_V1, directive.id(),
                            directive.description(), directive.rangeStart(), directive.rangeEnd(), directive.predicate(),
                            directive.metric(), directive.baseline(), directive.hypothesis(),
                            directive.expectedRelation().name(), directive.comparisonTolerance())));
        }
        if (lifecycle.getPendingFollowUp() != null) {
            var followUp = lifecycle.getPendingFollowUp();
            return new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_FOLLOW_UP_STUDY,
                    objectMapper.valueToTree(new ResearchParameterOverride(ResearchParameterOverride.LEGACY_FOLLOW_UP_V1,
                            followUp.param1(), followUp.param2(), followUp.predictedScore(), followUp.priorMethodology())));
        }
        return ResearchDraftExecutionIntent.standardCatalog();
    }

    private record SelectionSource(UUID subjectId, BotLifecycleState lifecycle) { }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.getNodeFactory().textNode(json);
        }
    }
}
