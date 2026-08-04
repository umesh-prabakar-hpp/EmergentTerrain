package com.emergentterrain.research.api;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.research.UniversityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Co-located implementation of the research tick boundary. */
@Service
@ConditionalOnBean(UniversityService.class)
@ConditionalOnProperty(name = "app.research-api.mode", havingValue = "local", matchIfMissing = true)
public class LocalResearchTickGateway implements ResearchTickGateway {

    private final UniversityService universityService;

    public LocalResearchTickGateway(UniversityService universityService) {
        this.universityService = universityService;
    }

    @Override
    public void onGameTick(UUID roomId, List<RoomBot> activeBots, long gameTick,
                           GovernmentState governmentState) {
        universityService.maybeTick(roomId, activeBots, gameTick, governmentState);
    }
}
