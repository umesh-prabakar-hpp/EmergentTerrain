package com.emergentterrain.researchservice;

import com.emergentterrain.government.GovernmentState;
import com.emergentterrain.researchcontract.ResearchBotSnapshot;
import com.emergentterrain.researchcontract.ResearchLifecycleExecution;

import java.util.List;

/** The minimum game-owned context that the autonomous research cycle consumes. */
public record ResearchGameContext(long gameTick, GovernmentState governmentState,
                                  List<ResearchBotSnapshot> activeBots,
                                  ResearchLifecycleExecution lifecycleExecution) {

    public ResearchGameContext {
        activeBots = activeBots == null ? List.of() : List.copyOf(activeBots);
    }

    public ResearchGameContext(long gameTick, GovernmentState governmentState,
                               List<ResearchBotSnapshot> activeBots) {
        this(gameTick, governmentState, activeBots, null);
    }
}
