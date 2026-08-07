package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.repo.ResearchPaperRepository;
import com.emergentterrain.researchcontract.ResearchTopicReselectionPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Durable game-to-research command adapter used after the co-located compatibility mode. */
@Service
@ConditionalOnProperty(name = "app.research-api.mode", havingValue = "outbox")
public class OutboxResearchTickGateway implements ResearchTickGateway {

    private final GameResearchWorkMapper mapper;
    private final ResearchWorkOutboxService outbox;
    private final ResearchPaperRepository papers;
    private final ResearchTopicReselectionPolicy topicReselectionPolicy;
    private final int topicHistoryWindow;

    public OutboxResearchTickGateway(GameResearchWorkMapper mapper, ResearchWorkOutboxService outbox,
                                    ResearchPaperRepository papers,
                                    @Value("${app.research.topic-clone-backoff.enabled:false}") boolean topicCloneEnabled,
                                    @Value("${app.research.topic-clone-backoff.window-size:5}") int topicHistoryWindow,
                                    @Value("${app.research.topic-clone-backoff.max-retries:3}") int maxRetries,
                                    @Value("${app.research.low-yield-backoff.enabled:false}") boolean lowYieldEnabled,
                                    @Value("${app.research.low-yield-backoff.peer-ceiling:0.60}") double lowYieldPeerCeiling,
                                    @Value("${app.research.low-yield-backoff.null-decay:0.70}") double lowYieldNullDecay) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.papers = papers;
        this.topicHistoryWindow = Math.max(1, topicHistoryWindow);
        this.topicReselectionPolicy = new ResearchTopicReselectionPolicy(
                ResearchTopicReselectionPolicy.LEGACY_TOPIC_BACKOFF_V1, topicCloneEnabled,
                lowYieldEnabled, Math.max(0, maxRetries), lowYieldPeerCeiling, lowYieldNullDecay);
    }

    @Override
    public void onGameTick(UUID roomId, List<RoomBot> activeBots, long gameTick, GovernmentState governmentState) {
        outbox.enqueue(mapper.fromTick(roomId, activeBots, gameTick, governmentState,
                papers.findByRoomIdOrderByTickDesc(roomId, PageRequest.of(0, topicHistoryWindow)),
                topicReselectionPolicy));
    }
}
