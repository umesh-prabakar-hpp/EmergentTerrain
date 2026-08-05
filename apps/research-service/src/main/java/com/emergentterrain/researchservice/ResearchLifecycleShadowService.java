package com.emergentterrain.researchservice;

import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only parity check before extracting lifecycle transitions from {@code UniversityService}.
 * It compares research-owned state with the game API snapshot, never mutable game entities.
 */
@Service
public class ResearchLifecycleShadowService {

    private static final Logger log = LoggerFactory.getLogger(ResearchLifecycleShadowService.class);

    private final ResearchBotStateRepository states;
    private final ResearchLifecycleShadowRecordRepository records;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public ResearchLifecycleShadowService(ResearchBotStateRepository states,
                                          ResearchLifecycleShadowRecordRepository records,
                                          ObjectMapper objectMapper,
                                          @Value("${app.research-service.lifecycle-shadow.enabled:false}") boolean enabled) {
        this.states = states;
        this.records = records;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Transactional
    public void compareAgainstGameSnapshot(UUID roomId, long gameTick,
                                           List<ResearchBotSnapshot> activeBots) {
        if (!enabled) {
            return;
        }
        Map<UUID, ResearchBotState> byBotId = new HashMap<>();
        for (ResearchBotState state : states.findByRoomId(roomId)) {
            byBotId.put(state.getBotId(), state);
        }

        compare(roomId, gameTick, activeBots, snapshot -> {
            ResearchBotState state = byBotId.get(snapshot.botId());
            return state == null ? null : state.getLifecycleJson();
        });
    }

    /** Compares a research-owned predicted stream with the same game snapshot. */
    @Transactional
    public void compareProjectedAgainstGameSnapshot(UUID roomId, long gameTick,
                                                     List<ResearchBotSnapshot> predictedBots,
                                                     List<ResearchBotSnapshot> gameBots) {
        Map<UUID, String> predicted = new HashMap<>();
        for (ResearchBotSnapshot bot : predictedBots) {
            if (bot.botId() != null) {
                predicted.put(bot.botId(), bot.lifecycleJson());
            }
        }
        compare(roomId, gameTick, gameBots, snapshot -> predicted.get(snapshot.botId()));
    }

    private void compare(UUID roomId, long gameTick, List<ResearchBotSnapshot> gameBots,
                         java.util.function.Function<ResearchBotSnapshot, String> candidateLifecycle) {
        if (!enabled) {
            return;
        }
        int matched = 0;
        int mismatched = 0;
        for (ResearchBotSnapshot bot : gameBots) {
            if (bot.botId() == null) {
                continue;
            }
            String candidate = candidateLifecycle.apply(bot);
            if (candidate == null || !sameLifecycle(candidate, bot.lifecycleJson())) {
                mismatched++;
            } else {
                matched++;
            }
        }
        records.save(new ResearchLifecycleShadowRecord(roomId, gameTick, gameBots.size(), matched, mismatched));
        if (mismatched > 0) {
            log.info("research lifecycle shadow mismatch roomId={} tick={} matched={} mismatched={}",
                    roomId, gameTick, matched, mismatched);
        }
    }

    private boolean sameLifecycle(String researchJson, String legacyJson) {
        try {
            BotLifecycleState research = objectMapper.readValue(blankAsObject(researchJson), BotLifecycleState.class);
            BotLifecycleState legacy = objectMapper.readValue(blankAsObject(legacyJson), BotLifecycleState.class);
            return research.getAgeTicks() == legacy.getAgeTicks()
                    && research.getLifecycle() == legacy.getLifecycle()
                    && research.getResearchStatus() == legacy.getResearchStatus()
                    && research.getPublishedCount() == legacy.getPublishedCount()
                    && research.getRejectedCount() == legacy.getRejectedCount()
                    && Double.compare(research.getReputation(), legacy.getReputation()) == 0
                    && Double.compare(research.getSkepticismLevel(), legacy.getSkepticismLevel()) == 0
                    && research.getLastCompetencyTick() == legacy.getLastCompetencyTick();
        } catch (Exception e) {
            return false;
        }
    }

    private static String blankAsObject(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }
}
