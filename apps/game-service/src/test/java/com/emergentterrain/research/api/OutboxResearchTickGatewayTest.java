package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.research.BotGenome;
import com.emergentterrain.research.BotLifecycleState;
import com.emergentterrain.research.ResearchPaper;
import com.emergentterrain.researchcontract.ResearchDraftSelectionInput;
import com.emergentterrain.researchcontract.ResearchTopicReselectionPolicy;
import com.emergentterrain.researchcontract.ResearchWorkRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void realMapperCarriesTheFrozenHistoryAndPolicyInTheDurableRequest() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        UUID roomId = UUID.randomUUID();
        UUID botId = UUID.randomUUID();
        RoomBot bot = mock(RoomBot.class);
        BotLifecycleState state = new BotLifecycleState();
        state.setGenome(new BotGenome("MATHEMATICS", "COMPUTATIONAL", new double[] {1, 1, 1, 1, 1}, 0.2, 1));
        state.setLifecycle(BotLifecycleState.Phase.ADULT);
        state.setResearchStatus(BotLifecycleState.ResearchStatus.QUALIFIED);
        when(bot.getId()).thenReturn(botId);
        when(bot.getType()).thenReturn("AGENT");
        when(bot.getLifecycleJson()).thenReturn(json.writeValueAsString(state));
        ResearchPaperRepository papers = mock(ResearchPaperRepository.class);
        ResearchPaper paper = mock(ResearchPaper.class);
        when(paper.domain()).thenReturn("MATHEMATICS");
        when(paper.topic()).thenReturn("Prime Density");
        when(paper.status()).thenReturn("REJECTED");
        when(paper.peerScore()).thenReturn(0.25);
        when(papers.findByRoomIdOrderByTickDesc(eq(roomId), any(Pageable.class))).thenReturn(List.of(paper));
        ResearchWorkOutboxService outbox = mock(ResearchWorkOutboxService.class);
        OutboxResearchTickGateway gateway = new OutboxResearchTickGateway(
                new GameResearchWorkMapper(json, false), outbox, papers, true, 5, 3, true, 0.60, 0.70);

        gateway.onGameTick(roomId, List.of(bot), 43L, null);

        var captured = forClass(ResearchWorkRequest.class);
        verify(outbox).enqueue(captured.capture());
        ResearchDraftSelectionInput input = json.treeToValue(captured.getValue().contextSnapshot().path("draftSelection"),
                ResearchDraftSelectionInput.class);
        assertThat(input.topicHistory().entries()).hasSize(1);
        assertThat(input.topicReselectionPolicy()).isEqualTo(
                new ResearchTopicReselectionPolicy("legacy-topic-backoff-v1", true, true, 3, 0.60, 0.70));
    }
}
