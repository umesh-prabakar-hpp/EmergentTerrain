package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.RoomStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Compatibility source used while the two services still share the migration database. */
@Service
@ConditionalOnProperty(name = "app.research-service.game-context.mode", havingValue = "database",
        matchIfMissing = true)
public class DatabaseResearchGameContextProvider implements ResearchGameContextProvider {

    private final RoomStateRepository states;
    private final ObjectMapper objectMapper;

    public DatabaseResearchGameContextProvider(RoomStateRepository states, ObjectMapper objectMapper) {
        this.states = states;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResearchGameContext contextFor(Room room) {
        GovernmentState government = states.findByRoom(room)
                .map(state -> parseGovernment(state.getGovernmentStateJson()))
                .orElse(null);
        return new ResearchGameContext(government == null ? 0L : government.getTick(), government);
    }

    private GovernmentState parseGovernment(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, GovernmentState.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
