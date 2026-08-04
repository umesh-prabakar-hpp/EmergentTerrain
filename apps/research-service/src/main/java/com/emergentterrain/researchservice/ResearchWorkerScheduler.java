package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.RoomBotRepository;
import com.emergentterrain.repo.RoomRepository;
import com.emergentterrain.research.UniversityService;
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
    private final ResearchGameContextProvider gameContext;
    private final ResearchWorkerCheckpointRepository checkpoints;
    private final UniversityService university;

    public ResearchWorkerScheduler(RoomRepository rooms, RoomBotRepository bots,
                                   ResearchGameContextProvider gameContext,
                                   ResearchWorkerCheckpointRepository checkpoints,
                                   UniversityService university) {
        this.rooms = rooms;
        this.bots = bots;
        this.gameContext = gameContext;
        this.checkpoints = checkpoints;
        this.university = university;
    }

    @Scheduled(fixedDelayString = "${app.research-service.worker.interval-ms:1000}")
    public void runResearchTicks() {
        for (Room room : rooms.findAll()) {
            try {
                List<RoomBot> active = bots.findByRoom_IdAndStatus(room.getId(), RoomBot.BotStatus.ACTIVE);
                if (active.isEmpty()) {
                    continue;
                }
                ResearchGameContext context = gameContext.contextFor(room);
                long governmentTick = context.gameTick();
                long lastTick = checkpoints.findById(room.getId())
                        .map(ResearchWorkerCheckpoint::getLastTick)
                        .orElse(governmentTick);
                long tick = Math.max(lastTick, governmentTick) + 1;
                university.maybeTick(room.getId(), active, tick, context.governmentState());
                checkpoints.save(new ResearchWorkerCheckpoint(room.getId(), tick));
            } catch (Exception e) {
                log.error("Research-only tick failed for roomId={}", room.getId(), e);
            }
        }
    }

}
