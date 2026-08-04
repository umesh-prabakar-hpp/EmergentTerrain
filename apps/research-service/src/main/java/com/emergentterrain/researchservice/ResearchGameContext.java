package com.emergentterrain.researchservice;

import com.emergentterrain.government.GovernmentState;

/** The minimum game-owned context that the autonomous research cycle consumes. */
public record ResearchGameContext(long gameTick, GovernmentState governmentState) {
}
