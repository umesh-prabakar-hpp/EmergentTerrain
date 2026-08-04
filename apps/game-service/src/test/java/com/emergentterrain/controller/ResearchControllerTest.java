package com.emergentterrain.controller;

import com.emergentterrain.research.api.ResearchQueryClient;
import com.emergentterrain.researchcontract.ResearchPaperSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchControllerTest {

    @Test
    void v1AndLegacyQueriesUseTheResearchClientAndClampTheLimit() {
        UUID roomId = UUID.randomUUID();
        AtomicInteger observedLimit = new AtomicInteger();
        ResearchQueryClient client = new ResearchQueryClient() {
            @Override
            public List<ResearchPaperSummary> getPublishedPapers(UUID requestedRoomId, int limit) {
                assertThat(requestedRoomId).isEqualTo(roomId);
                observedLimit.set(limit);
                return List.of();
            }

            @Override
            public Map<String, Object> getStats(UUID requestedRoomId) {
                assertThat(requestedRoomId).isEqualTo(roomId);
                return Map.of("totalPublished", 0L);
            }
        };

        ResearchController controller = new ResearchController(client);

        assertThat(controller.getPapers(roomId, 999).getBody()).isEmpty();
        assertThat(observedLimit.get()).isEqualTo(500);
        assertThat(controller.getPapers(roomId, -1).getBody()).isEmpty();
        assertThat(observedLimit.get()).isEqualTo(1);
        assertThat(controller.getStats(roomId).getBody()).containsEntry("totalPublished", 0L);
    }
}
