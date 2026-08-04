package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.repo.RoomStateRepository;
import com.emergentterrain.research.UniversityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Runs the existing university cycle without RoomService or the gameplay maintenance loop.
 *
 * <p>It reads the last persisted government state as research context and persists an independent,
 * monotonic research tick per room. A future game-snapshot API replaces these database reads when
 * the game and research databases are fully separated.</p>
 */
@Service
@ConditionalOnProperty(name = "app.research-service.worker.enabled", havingValue = "true",
        matchIfMissing = true)
public class ResearchWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResearchWorkerScheduler.class);

    private final RoomRepository rooms;
    private final RoomBotRepository bots;
    private final RoomStateRepository states;
    private final ResearchWorkerCheckpointRepository checkpoints;
    private final UniversityService university;
    private final ObjectMapper objectMapper;

    public ResearchWorkerScheduler(RoomRepository rooms, RoomBotRepository bots,
                                   RoomStateRepository states,
                                   ResearchWorkerCheckpointRepository checkpoints,
                                   UniversityService university,
                                   ObjectMapper objectMapper) {
        this.rooms = rooms;
        this.bots = bots;
        this.states = states;
        this.checkpoints = checkpoints;
        this.university = university;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.research-service.worker.interval-ms:1000}")
    public void runResearchTicks() {
        for (Room room : rooms.findAll()) {
            try {
                List<RoomBot> active = bots.findByRoom_IdAndStatus(room.getId(), RoomBot.BotStatus.ACTIVE);
                if (active.isEmpty()) {
                    continue;
                }
                GovernmentState government = states.findByRoom(room)
                        .map(state -> parseGovernment(state.getGovernmentStateJson(), room.getId()))
                        .orElse(null);
                long governmentTick = government == null ? 0L : government.getTick();
                long lastTick = checkpoints.findById(room.getId())
                        .map(ResearchWorkerCheckpoint::getLastTick)
                        .orElse(governmentTick);
                long tick = Math.max(lastTick, governmentTick) + 1;
                university.maybeTick(room.getId(), active, tick, government);
                checkpoints.save(new ResearchWorkerCheckpoint(room.getId(), tick));
            } catch (Exception e) {
                log.error("Research-only tick failed for roomId={}", room.getId(), e);
            }
        }
    }

    private GovernmentState parseGovernment(String json, UUID roomId) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, GovernmentState.class);
        } catch (Exception e) {
            log.warn("Research-only tick ignoring malformed government state for roomId={}", roomId);
            return null;
        }
    }
}
