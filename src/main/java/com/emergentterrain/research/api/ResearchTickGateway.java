package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;

import java.util.List;
import java.util.UUID;

/**
 * Compatibility command boundary for a gameplay tick.
 *
 * <p>The payload still contains in-process game state while the research worker is co-located.
 * A later extraction replaces this with the versioned snapshot/action HTTP contract; gameplay code
 * is already isolated from {@code UniversityService} by this interface.</p>
 */
public interface ResearchTickGateway {

    void onGameTick(UUID roomId, List<RoomBot> activeBots, long gameTick,
                    GovernmentState governmentState);
}
