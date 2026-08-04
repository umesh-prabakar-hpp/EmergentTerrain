package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.government.GovernmentState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/** v1 HTTP context reader used once game-owned state is no longer shared through PostgreSQL. */
@Service
@ConditionalOnProperty(name = "app.research-service.game-context.mode", havingValue = "remote")
public class RemoteResearchGameContextProvider implements ResearchGameContextProvider {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public RemoteResearchGameContextProvider(RestClient.Builder builder, ObjectMapper objectMapper,
                                             @Value("${app.research-service.game-context.base-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public ResearchGameContext contextFor(Room room) {
        RemoteContext response = client.get()
                .uri("/api/v1/game/rooms/{roomId}/research-context", room.getId())
                .retrieve()
                .body(RemoteContext.class);
        if (response == null || response.governmentState() == null || response.governmentState().isEmpty()) {
            return new ResearchGameContext(response == null ? 0L : response.gameTick(), null);
        }
        try {
            return new ResearchGameContext(response.gameTick(),
                    objectMapper.treeToValue(response.governmentState(), GovernmentState.class));
        } catch (Exception e) {
            throw new IllegalStateException("Game research-context response contains invalid government state", e);
        }
    }

    private record RemoteContext(UUID roomId, long gameTick, JsonNode governmentState) {
    }
}
