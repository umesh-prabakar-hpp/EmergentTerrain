package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.MathPhysicsEngine;
import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.research.conjecture.ConjectureTopicProvider;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.researchcontract.ResearchConjectureSpecification;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSnapshot;
import com.emergentterrain.researchcontract.ResearchConjectureQueueSelection;
import com.emergentterrain.researchcontract.ResearchDraftCandidate;
import com.emergentterrain.researchcontract.ResearchDraftExecutionIntent;
import com.emergentterrain.researchcontract.ResearchDraftSelection;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchParameterOverride;
import com.emergentterrain.researchcontract.ResearchSubject;
import com.emergentterrain.researchcontract.ResearchStudySpecification;
import com.emergentterrain.researchcontract.ResearchTopicOption;
import com.emergentterrain.researchcontract.ResearchTopicHistoryEntry;
import com.emergentterrain.researchcontract.ResearchTopicHistorySnapshot;
import com.emergentterrain.researchcontract.ResearchTopicReselectionPolicy;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.emergentterrain.researchcontract.ResearchSelectionSeed;
import com.emergentterrain.researchcontract.FrozenResearchCyclePlanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        return fromTick(workspaceId, activeBots, sourceRevision, governmentState, List.of(),
                ResearchTopicReselectionPolicy.disabled());
    }

    /**
     * Freezes history-aware selection input for parity testing and a later reviewed outbox cutover.
     * The production outbox uses the disabled-policy overload until its whole-path parity gate passes.
     */
    ResearchWorkRequest fromTick(UUID workspaceId, List<RoomBot> activeBots, long sourceRevision,
                                 GovernmentState governmentState, List<ResearchPaper> recentPapers,
                                 ResearchTopicReselectionPolicy topicReselectionPolicy) {
        List<ResearchSubject> subjects = activeBots.stream()
                .map(this::subjectFor)
                .toList();
        ObjectNode context = objectMapper.createObjectNode();
        context.set("governance", objectMapper.valueToTree(governmentState));
        context.put("subjectCount", subjects.size());
        context.set("draftSelection", objectMapper.valueToTree(withTopicHistory(
                standardSelectionInput(workspaceId, activeBots, sourceRevision), recentPapers,
                topicReselectionPolicy)));
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
        long seed = ResearchSelectionSeed.from(workspaceId, sourceRevision);
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

    /**
     * Maps a game-owned conjecture into the generic contract. A later selection-owner increment
     * will call this only after reproducing queue ordering and consumption semantics.
     */
    ResearchDraftExecutionIntent frozenConjectureIntent(Conjecture conjecture, String selectionReason) {
        return new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY,
                objectMapper.valueToTree(frozenConjectureSpecification(conjecture, selectionReason)));
    }

    /** Creates game-local reservation metadata from an already frozen generic conjecture selection. */
    ResearchWorkSourceReservation conjectureReservation(ResearchWorkRequest request,
                                                         ResearchConjectureSpecification conjecture) {
        if (conjecture == null || conjecture.sourceId() == null || conjecture.sourceId().isBlank()) {
            throw new IllegalArgumentException("A conjecture reservation requires a frozen source id");
        }
        return new ResearchWorkSourceReservation(request.requestId(), "CONJECTURE", conjecture.sourceId());
    }

    /**
     * Builds an immutable, game-owned submission from an already observed queue decision.
     * It never queries, consumes, or marks the live queue.
     */
    FrozenResearchWorkSubmission frozenConjectureSubmission(ResearchWorkRequest request,
                                                            ResearchConjectureQueueSelection selection) {
        if (selection == null || selection.specification() == null) {
            throw new IllegalArgumentException("A frozen conjecture submission requires a selected conjecture");
        }
        ResearchDraftSelectionInput input = draftSelectionInput(request);
        ObjectNode context = request.contextSnapshot().deepCopy();
        context.set("draftSelection", objectMapper.valueToTree(withConjectureQueueSelection(input, selection)));
        ResearchWorkRequest frozen = new ResearchWorkRequest(request.requestId(), request.workspaceId(), request.workType(),
                request.sourceRevision(), request.requestedAt(), request.subjects(), context);
        return new FrozenResearchWorkSubmission(frozen, conjectureReservation(frozen, selection.specification()));
    }

    /**
     * Builds a reserved submission from an immutable queue observation. This is an audit/cutover
     * seam only: callers supply the snapshot and this method never queries or consumes game state.
     */
    FrozenResearchWorkSubmission frozenConjectureSubmissionFromSnapshot(ResearchWorkRequest request,
                                                                          ResearchConjectureQueueSnapshot snapshot) {
        if (ResearchDraftExecutionIntent.DEFERRED_PARAMETERIZED_STUDY
                .equals(draftSelectionInput(request).executionIntent().kind())) {
            throw new IllegalArgumentException("Frozen queue observation did not select a conjecture");
        }
        FrozenResearchQueueSnapshotPreparation preparation = frozenQueueSnapshotPreparation(request, snapshot);
        return new FrozenResearchWorkSubmission(preparation.request(), preparation.sourceReservation()
                .orElseThrow(() -> new IllegalArgumentException("Frozen queue observation did not select a conjecture")));
    }

    /**
     * Freezes a read-only queue observation into generic planning input, including an ordinary
     * queue miss that must consume its recorded pre-topic draw. It performs no source reservation.
     */
    ResearchWorkRequest frozenQueueSnapshotRequest(ResearchWorkRequest request,
                                                    ResearchConjectureQueueSnapshot snapshot) {
        ResearchDraftSelectionInput input = draftSelectionInput(request);
        ResearchConjectureQueueSelection selection = FrozenResearchCyclePlanner
                .observeConjectureQueue(input, snapshot).conjectureSelection();
        ObjectNode context = request.contextSnapshot().deepCopy();
        context.set("draftSelection", objectMapper.valueToTree(
                withConjectureQueueSelection(input, selection, snapshot)));
        return new ResearchWorkRequest(request.requestId(), request.workspaceId(), request.workType(),
                request.sourceRevision(), request.requestedAt(), request.subjects(), context);
    }

    /** Prepares either a source-reserved selected conjecture or an ordinary frozen planning request. */
    FrozenResearchQueueSnapshotPreparation frozenQueueSnapshotPreparation(ResearchWorkRequest request,
                                                                            ResearchConjectureQueueSnapshot snapshot) {
        ResearchWorkRequest frozen = frozenQueueSnapshotRequest(request, snapshot);
        ResearchDraftSelectionInput input = draftSelectionInput(frozen);
        if (!ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY.equals(input.executionIntent().kind())) {
            return new FrozenResearchQueueSnapshotPreparation(frozen, Optional.empty());
        }
        ResearchConjectureSpecification conjecture = read(input.executionIntent().payload(),
                ResearchConjectureSpecification.class, "conjecture specification");
        return new FrozenResearchQueueSnapshotPreparation(frozen,
                Optional.of(conjectureReservation(frozen, conjecture)));
    }

    ResearchConjectureQueueSnapshot frozenConjectureQueueSnapshot(String domain, double ordinarySelectionProbability,
                                                                   List<Conjecture> promoted,
                                                                   List<Conjecture> ordinary) {
        return new ResearchConjectureQueueSnapshot(ResearchConjectureQueueSnapshot.LEGACY_CONJECTURE_QUEUE_V1,
                domain, ordinarySelectionProbability,
                promoted == null ? List.of() : promoted.stream().map(conjecture ->
                        frozenConjectureSpecification(conjecture, ResearchConjectureSpecification.PROMOTED_ANOMALY)).toList(),
                ordinary == null ? List.of() : ordinary.stream().map(conjecture ->
                        frozenConjectureSpecification(conjecture, ResearchConjectureSpecification.QUEUE_CANDIDATE)).toList());
    }

    /** Maps an already-read-only legacy queue observation without selecting, reserving, or consuming it. */
    ResearchConjectureQueueSnapshot frozenConjectureQueueSnapshot(
            ConjectureTopicProvider.QueueObservation observation) {
        if (observation == null) throw new IllegalArgumentException("A queue observation is required");
        return frozenConjectureQueueSnapshot(observation.substrate(), observation.ordinarySelectionProbability(),
                observation.promotedCandidates(), observation.ordinaryCandidates());
    }

    /** Applies an already-observed, read-only queue decision without consuming game queue state. */
    ResearchDraftSelectionInput withConjectureQueueSelection(ResearchDraftSelectionInput input,
                                                              ResearchConjectureQueueSelection selection) {
        return withConjectureQueueSelection(input, selection, null);
    }

    /** Retains the bounded queue observation so research can audit the terminal selection independently. */
    ResearchDraftSelectionInput withConjectureQueueSelection(ResearchDraftSelectionInput input,
                                                              ResearchConjectureQueueSelection selection,
                                                              ResearchConjectureQueueSnapshot snapshot) {
        ResearchDraftExecutionIntent intent = selection.specification() == null ? input.executionIntent()
                : new ResearchDraftExecutionIntent(ResearchDraftExecutionIntent.DEFERRED_CONJECTURE_STUDY,
                        objectMapper.valueToTree(selection.specification()));
        return new ResearchDraftSelectionInput(input.selectorVersion(), input.selectionSeed(),
                input.topicSelectionMode(), input.candidates(), intent, input.topicHistory(),
                input.topicReselectionPolicy(), snapshot, selection.preTopicRandomDoubleDraws());
    }

    private ResearchConjectureSpecification frozenConjectureSpecification(Conjecture conjecture, String selectionReason) {
        if (conjecture == null || conjecture.id() == null) {
            throw new IllegalArgumentException("A frozen conjecture requires a persistent source id");
        }
        return new ResearchConjectureSpecification(ResearchConjectureSpecification.LEGACY_CONJECTURE_V1,
                conjecture.id().toString(), conjecture.domain(), conjecture.kind(), conjecture.lhs(), conjecture.rhs(),
                conjecture.rhs2(), conjecture.k(), conjecture.k2(), conjecture.c(), conjecture.confirmedRuns(),
                conjecture.p99Slack(), conjecture.captureWindowStart(), selectionReason);
    }

    /** Maps game-owned paper history into immutable generic input; selection replay is not enabled yet. */
    ResearchDraftSelectionInput withTopicHistory(ResearchDraftSelectionInput input,
                                                  List<ResearchPaper> recentPapers) {
        return withTopicHistory(input, recentPapers, ResearchTopicReselectionPolicy.disabled());
    }

    /** The game freezes its policy values with history; callers enable it only after parity approval. */
    ResearchDraftSelectionInput withTopicHistory(ResearchDraftSelectionInput input,
                                                  List<ResearchPaper> recentPapers,
                                                  ResearchTopicReselectionPolicy policy) {
        List<ResearchTopicHistoryEntry> entries = recentPapers == null ? List.of() : recentPapers.stream()
                .filter(paper -> paper != null && paper.domain() != null && !paper.domain().isBlank()
                        && paper.topic() != null && !paper.topic().isBlank() && paper.status() != null
                        && !paper.status().isBlank() && Double.isFinite(paper.peerScore()))
                .map(paper -> new ResearchTopicHistoryEntry(paper.domain(), paper.topic(), paper.status(),
                        paper.peerScore()))
                .toList();
        return new ResearchDraftSelectionInput(input.selectorVersion(), input.selectionSeed(),
                input.topicSelectionMode(), input.candidates(), input.executionIntent(),
                new ResearchTopicHistorySnapshot(ResearchTopicHistorySnapshot.LEGACY_TOPIC_HISTORY_V1, entries), policy);
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

    private ResearchDraftSelectionInput draftSelectionInput(ResearchWorkRequest request) {
        try {
            return objectMapper.treeToValue(request.contextSnapshot().path("draftSelection"),
                    ResearchDraftSelectionInput.class);
        } catch (Exception invalidInput) {
            throw new IllegalArgumentException("A frozen conjecture submission requires draftSelection input", invalidInput);
        }
    }

    private <T> T read(JsonNode payload, Class<T> type, String description) {
        try {
            return objectMapper.treeToValue(payload, type);
        } catch (Exception invalidPayload) {
            throw new IllegalArgumentException("Invalid frozen " + description + " payload", invalidPayload);
        }
    }
}
