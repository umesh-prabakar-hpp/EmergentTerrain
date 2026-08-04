package com.emergentterrain.researchservice;

import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.researchcontract.ResearchBotSnapshot;

import java.util.List;

/** The minimum game-owned context that the autonomous research cycle consumes. */
public record ResearchGameContext(long gameTick, GovernmentState governmentState,
                                  List<ResearchBotSnapshot> activeBots) {

    public ResearchGameContext {
        activeBots = activeBots == null ? List.of() : List.copyOf(activeBots);
    }
}
