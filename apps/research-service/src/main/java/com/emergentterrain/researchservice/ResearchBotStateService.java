package com.emergentterrain.researchservice;

import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Seeds and updates research ownership metadata without overwriting research lifecycle mutations. */
@Service
public class ResearchBotStateService {

    private final ResearchBotStateRepository states;

    public ResearchBotStateService(ResearchBotStateRepository states) {
        this.states = states;
    }

    @Transactional
    public void seedFromSnapshot(UUID roomId, ResearchGameContext context) {
        Map<UUID, ResearchBotState> existing = new HashMap<>();
        for (ResearchBotState state : states.findByRoomId(roomId)) {
            existing.put(state.getBotId(), state);
        }
        Set<UUID> selected = new HashSet<>();
        for (ResearchBotSnapshot snapshot : context.activeBots()) {
            if (snapshot.botId() == null) {
                continue;
            }
            selected.add(snapshot.botId());
            ResearchBotState state = existing.get(snapshot.botId());
            if (state == null) {
                states.save(new ResearchBotState(snapshot.botId(), roomId, snapshot.type(),
                        snapshot.lifecycleJson(), context.gameTick(), true));
            } else if (context.gameTick() >= state.getLastSourceGameTick()) {
                // Preserve lifecycleJson: after creation it is research-owned, not a game mirror.
                state.refreshSource(snapshot.type(), context.gameTick(), true);
                states.save(state);
            }
        }
        for (ResearchBotState state : existing.values()) {
            if (!selected.contains(state.getBotId()) && context.gameTick() >= state.getLastSourceGameTick()) {
                state.refreshSource(state.getBotType(), context.gameTick(), false);
                states.save(state);
            }
        }
    }
}
