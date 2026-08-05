package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import com.emergentterrain.researchcontract.ResearchLifecycleExecution;
import com.emergentterrain.researchcontract.ResearchLifecycleExecutionWindow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maintains an independent predicted lifecycle stream for the first deterministic extraction slice.
 * It intentionally models only legacy per-cycle ageing and reputation decay; all other mutations
 * remain visible as parity misses until independently extracted.
 */
@Service
public class ResearchLifecycleProjectionService {

    private static final long JUVENILE_TO_ADULT = 100L;
    private static final double REPUTATION_BASELINE = 0.2;
    private static final double REPUTATION_DECAY = 0.9999;

    private final ResearchLifecycleProjectionRepository projections;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public ResearchLifecycleProjectionService(ResearchLifecycleProjectionRepository projections,
                                              ObjectMapper objectMapper,
                                              @Value("${app.research-service.lifecycle-shadow.projector.enabled:false}")
                                              boolean enabled) {
        this.projections = projections;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Transactional
    public ProjectionSnapshot projectForParity(UUID roomId, ResearchGameContext context) {
        if (!enabled) {
            return ProjectionSnapshot.disabled();
        }
        ResearchLifecycleExecutionWindow window = replayWindow(context);
        if (window == null || !isValidWindow(window, context.gameTick())) {
            return ProjectionSnapshot.inconclusive();
        }
        Map<UUID, ResearchLifecycleProjection> current = new HashMap<>();
        for (ResearchLifecycleProjection projection : projections.findByRoomId(roomId)) {
            current.put(projection.getBotId(), projection);
        }
        if (current.values().stream().anyMatch(projection -> !canReplay(projection, window))) {
            return ProjectionSnapshot.inconclusive();
        }

        List<ResearchBotSnapshot> predicted = context.activeBots().stream()
                .filter(snapshot -> snapshot.botId() != null)
                .map(snapshot -> predict(roomId, context.gameTick(), window, current.get(snapshot.botId()), snapshot))
                .toList();
        return ProjectionSnapshot.ready(predicted);
    }

    private ResearchBotSnapshot predict(UUID roomId, long gameTick, ResearchLifecycleExecutionWindow window,
                                        ResearchLifecycleProjection projection, ResearchBotSnapshot gameSnapshot) {
        if (projection == null) {
            projections.save(new ResearchLifecycleProjection(gameSnapshot.botId(), roomId,
                    gameSnapshot.lifecycleJson(), gameTick, window.latestSequence(), window.epoch()));
            // A new stream begins from this post-tick snapshot; applying again would be an off-by-one error.
            return gameSnapshot;
        }
        String lifecycleJson = projection.getLifecycleJson();
        if (window.latestSequence() > projection.getLastExecutionSequence()) {
            for (ResearchLifecycleExecution execution : window.events()) {
                if (execution.sequence() > projection.getLastExecutionSequence() && execution.advanced()) {
                    lifecycleJson = projectAgeAndReputation(lifecycleJson, execution.adultToElder());
                }
            }
            projection.observe(lifecycleJson, gameTick, window.latestSequence(), window.epoch());
            projections.save(projection);
        }
        return new ResearchBotSnapshot(gameSnapshot.botId(), gameSnapshot.type(), lifecycleJson);
    }

    private ResearchLifecycleExecutionWindow replayWindow(ResearchGameContext context) {
        ResearchLifecycleExecutionWindow window = context.lifecycleExecutionWindow();
        if (window != null) {
            return window;
        }
        ResearchLifecycleExecution execution = context.lifecycleExecution();
        if (execution == null) {
            return null;
        }
        long sequence = execution.sequence();
        return new ResearchLifecycleExecutionWindow("legacy-single-event", sequence, sequence, List.of(execution));
    }

    private static boolean isValidWindow(ResearchLifecycleExecutionWindow window, long gameTick) {
        if (window.epoch() == null || window.epoch().isBlank() || window.events().isEmpty()
                || window.firstSequence() > window.latestSequence()) {
            return false;
        }
        long expected = window.firstSequence();
        for (ResearchLifecycleExecution event : window.events()) {
            if (event.sequence() != expected++ || event.gameTick() > gameTick
                    || (event.advanced() && event.adultToElder() == null)) {
                return false;
            }
        }
        return window.events().getLast().sequence() == window.latestSequence()
                && window.events().getLast().gameTick() == gameTick;
    }

    private static boolean canReplay(ResearchLifecycleProjection projection,
                                     ResearchLifecycleExecutionWindow window) {
        if (projection.getExecutionEpoch() == null) {
            return projection.getLastExecutionSequence() == 0L && window.firstSequence() == 1L;
        }
        if (!projection.getExecutionEpoch().equals(window.epoch())) {
            return false;
        }
        return projection.getLastExecutionSequence() >= window.firstSequence() - 1L
                && projection.getLastExecutionSequence() <= window.latestSequence();
    }

    private String projectAgeAndReputation(String lifecycleJson, long adultToElder) {
        try {
            JsonNode parsed = lifecycleJson == null || lifecycleJson.isBlank()
                    ? null : objectMapper.readTree(lifecycleJson);
            ObjectNode state = parsed instanceof ObjectNode object ? object.deepCopy()
                    : objectMapper.createObjectNode();
            long age = state.path("ageTicks").asLong(0L) + 1L;
            state.put("ageTicks", age);
            if (age >= adultToElder) {
                state.put("lifecycle", "ELDER");
            } else if (age >= JUVENILE_TO_ADULT) {
                state.put("lifecycle", "ADULT");
            }
            double reputation = state.path("reputation").asDouble(REPUTATION_BASELINE);
            state.put("reputation", REPUTATION_BASELINE
                    + (reputation - REPUTATION_BASELINE) * REPUTATION_DECAY);
            return state.toString();
        } catch (Exception e) {
            // Preserve malformed legacy JSON for a parity miss rather than inventing a replacement state.
            return lifecycleJson;
        }
    }

    public record ProjectionSnapshot(boolean enabled, boolean ready, List<ResearchBotSnapshot> bots) {
        static ProjectionSnapshot disabled() { return new ProjectionSnapshot(false, false, List.of()); }
        static ProjectionSnapshot inconclusive() { return new ProjectionSnapshot(true, false, List.of()); }
        static ProjectionSnapshot ready(List<ResearchBotSnapshot> bots) {
            return new ProjectionSnapshot(true, true, List.copyOf(bots));
        }
    }
}
