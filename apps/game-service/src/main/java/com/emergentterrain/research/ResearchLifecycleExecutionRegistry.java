package com.emergentterrain.research;

import com.emergentterrain.researchcontract.ResearchLifecycleExecution;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes the outcome of the legacy lifecycle gate for the game-owned context endpoint.
 * Entries are in-memory observations only; game state remains the durable authority during shadowing.
 */
@Service
public class ResearchLifecycleExecutionRegistry {

    private final ConcurrentHashMap<UUID, ResearchLifecycleExecution> latest = new ConcurrentHashMap<>();

    public void skipped(UUID roomId, long gameTick) {
        latest.put(roomId, new ResearchLifecycleExecution(gameTick, false, null));
    }

    public void advanced(UUID roomId, long gameTick, long adultToElder) {
        latest.put(roomId, new ResearchLifecycleExecution(gameTick, true, adultToElder));
    }

    /** Only the observation for the snapshot's exact game tick is safe for a remote projector. */
    public Optional<ResearchLifecycleExecution> forGameTick(UUID roomId, long gameTick) {
        return Optional.ofNullable(latest.get(roomId)).filter(execution -> execution.gameTick() == gameTick);
    }
}
