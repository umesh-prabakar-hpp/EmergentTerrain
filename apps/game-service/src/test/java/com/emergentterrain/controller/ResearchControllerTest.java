package com.emergentterrain.controller;

import com.emergentterrain.research.api.ResearchQueryClient;
import com.emergentterrain.researchcontract.ResearchPaperSummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.web.client.ResourceAccessException;

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

        ResearchController controller = new ResearchController(Optional.of(client));

        assertThat(controller.getPapers(roomId, 999).getBody()).isEmpty();
        assertThat(observedLimit.get()).isEqualTo(500);
        assertThat(controller.getPapers(roomId, -1).getBody()).isEmpty();
        assertThat(observedLimit.get()).isEqualTo(1);
        assertThat(controller.getStats(roomId).getBody()).containsEntry("totalPublished", 0L);
    }

    @Test
    void disabledResearchReturnsAStableEmptyReadModel() {
        UUID roomId = UUID.randomUUID();
        ResearchController controller = new ResearchController(Optional.empty());

        assertThat(controller.getPapers(roomId, 50).getBody()).isEmpty();
        assertThat(controller.getStats(roomId).getBody())
                .containsEntry("status", "DISABLED")
                .containsEntry("totalPublished", 0L);
    }

    @Test
    void remoteResearchFailuresBecomeServiceUnavailable() {
        ResearchQueryClient unavailable = new ResearchQueryClient() {
            @Override
            public List<ResearchPaperSummary> getPublishedPapers(UUID roomId, int limit) {
                throw new ResourceAccessException("offline");
            }

            @Override
            public Map<String, Object> getStats(UUID roomId) {
                throw new ResourceAccessException("offline");
            }
        };
        ResearchController controller = new ResearchController(Optional.of(unavailable));

        assertThat(controller.getPapers(UUID.randomUUID(), 50).getStatusCode().value()).isEqualTo(503);
        assertThat(controller.getStats(UUID.randomUUID()).getStatusCode().value()).isEqualTo(503);
    }
}
