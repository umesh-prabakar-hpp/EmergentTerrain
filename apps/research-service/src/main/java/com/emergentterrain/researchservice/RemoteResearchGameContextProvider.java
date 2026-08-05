package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.researchcontract.ResearchExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** v1 HTTP context reader used once game-owned state is no longer shared through PostgreSQL. */
@Service
@ConditionalOnProperty(name = "app.research-service.game-context.mode", havingValue = "remote")
public class RemoteResearchGameContextProvider implements ResearchGameContextProvider {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public RemoteResearchGameContextProvider(RestClient.Builder builder, ObjectMapper objectMapper,
                                             @Value("${app.research-service.game-context.base-url}") String baseUrl,
                                             @Value("${app.research-service.game-context.api-key:}") String apiKey) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
    }

    @Override
    public ResearchGameContext contextFor(Room room) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("RESEARCH_CONTEXT_API_KEY is required for remote game context");
        }
        ResearchExecutionContext response = client.get()
                .uri("/api/v1/game/rooms/{roomId}/research-context", room.getId())
                .header("X-Research-Service-Key", apiKey)
                .retrieve()
                .body(ResearchExecutionContext.class);
        if (response == null || response.governmentState() == null || response.governmentState().isEmpty()) {
            return new ResearchGameContext(response == null ? 0L : response.gameTick(), null,
                    response == null ? java.util.List.of() : response.activeBots(),
                    response == null ? null : response.lifecycleExecution(),
                    response == null ? null : response.lifecycleExecutionWindow());
        }
        try {
            return new ResearchGameContext(response.gameTick(),
                    objectMapper.treeToValue(response.governmentState(), GovernmentState.class),
                    response.activeBots(), response.lifecycleExecution(), response.lifecycleExecutionWindow());
        } catch (Exception e) {
            throw new IllegalStateException("Game research-context response contains invalid government state", e);
        }
    }
}
