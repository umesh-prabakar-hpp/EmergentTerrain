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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs the existing university cycle without RoomService or the gameplay maintenance loop.
 *
 * <p>It obtains game-owned government and active-bot selection context from a provider and
 * persists an independent, monotonic research tick per room. Mutable bot entities remain on the
 * migration database until bot-state ownership moves into the research service.</p>
 */
@Service
@ConditionalOnProperty(name = "app.research-service.worker.enabled", havingValue = "true",
        matchIfMissing = true)
public class ResearchWorkerScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResearchWorkerScheduler.class);

    private final RoomRepository rooms;
    private final RoomBotRepository bots;
    private final ResearchGameContextProvider gameContext;
    private final ResearchBotReplicaService botReplicas;
    private final ResearchWorkerCheckpointRepository checkpoints;
    private final UniversityService university;

    public ResearchWorkerScheduler(RoomRepository rooms, RoomBotRepository bots,
                                   ResearchGameContextProvider gameContext,
                                   ResearchBotReplicaService botReplicas,
                                   ResearchWorkerCheckpointRepository checkpoints,
                                   UniversityService university) {
        this.rooms = rooms;
        this.bots = bots;
        this.gameContext = gameContext;
        this.botReplicas = botReplicas;
        this.checkpoints = checkpoints;
        this.university = university;
    }

    @Scheduled(fixedDelayString = "${app.research-service.worker.interval-ms:1000}")
    public void runResearchTicks() {
        for (Room room : rooms.findAll()) {
            try {
                ResearchGameContext context = gameContext.contextFor(room);
                botReplicas.apply(room.getId(), context);
                List<RoomBot> active = activeBotsFor(room, context);
                if (active.isEmpty()) {
                    continue;
                }
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

    private List<RoomBot> activeBotsFor(Room room, ResearchGameContext context) {
        if (context.activeBots().isEmpty()) {
            return List.of();
        }
        Map<java.util.UUID, RoomBot> persisted = bots.findAllById(context.activeBots().stream()
                        .map(snapshot -> snapshot.botId())
                        .filter(java.util.Objects::nonNull)
                        .toList())
                .stream()
                .collect(Collectors.toMap(RoomBot::getId, Function.identity()));
        return context.activeBots().stream()
                .map(snapshot -> persisted.get(snapshot.botId()))
                .filter(bot -> bot != null
                        && RoomBot.BotStatus.ACTIVE == bot.getStatus()
                        && room.getId().equals(bot.getRoom().getId()))
                .toList();
    }
}
