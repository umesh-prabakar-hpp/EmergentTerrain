package com.emergentterrain.research.api;

import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.researchcontract.ResearchTopicReselectionPolicy;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxResearchTickGatewayTest {
    @Test
    void freezesTheGameOwnedRecentHistoryAndEnabledPolicyIntoOutboxWork() {
        UUID roomId = UUID.randomUUID();
        GameResearchWorkMapper mapper = mock(GameResearchWorkMapper.class);
        ResearchWorkOutboxService outbox = mock(ResearchWorkOutboxService.class);
        ResearchPaperRepository papers = mock(ResearchPaperRepository.class);
        ResearchPaper paper = mock(ResearchPaper.class);
        ResearchWorkRequest request = new ResearchWorkRequest(UUID.randomUUID(), roomId, "RESEARCH_CYCLE", 42,
                Instant.now(), List.of(), null);
        when(papers.findByRoomIdOrderByTickDesc(eq(roomId), any(Pageable.class))).thenReturn(List.of(paper));
        when(mapper.fromTick(eq(roomId), any(), eq(42L), eq(null), eq(List.of(paper)),
                any(ResearchTopicReselectionPolicy.class))).thenReturn(request);
        OutboxResearchTickGateway gateway = new OutboxResearchTickGateway(mapper, outbox, papers,
                true, 5, 3, true, 0.60, 0.70);

        gateway.onGameTick(roomId, List.of(), 42L, null);

        verify(papers).findByRoomIdOrderByTickDesc(eq(roomId), any(Pageable.class));
        verify(mapper).fromTick(eq(roomId), any(), eq(42L), eq(null), eq(List.of(paper)),
                eq(new ResearchTopicReselectionPolicy("legacy-topic-backoff-v1", true, true, 3, 0.60, 0.70)));
        verify(outbox).enqueue(request);
    }
}
