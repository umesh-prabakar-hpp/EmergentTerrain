package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Durable game-to-research command adapter used after the co-located compatibility mode. */
@Service
@ConditionalOnProperty(name = "app.research-api.mode", havingValue = "outbox")
public class OutboxResearchTickGateway implements ResearchTickGateway {

    private final GameResearchWorkMapper mapper;
    private final ResearchWorkOutboxService outbox;

    public OutboxResearchTickGateway(GameResearchWorkMapper mapper, ResearchWorkOutboxService outbox) {
        this.mapper = mapper;
        this.outbox = outbox;
    }

    @Override
    public void onGameTick(UUID roomId, List<RoomBot> activeBots, long gameTick, GovernmentState governmentState) {
        outbox.enqueue(mapper.fromTick(roomId, activeBots, gameTick, governmentState));
    }
}
