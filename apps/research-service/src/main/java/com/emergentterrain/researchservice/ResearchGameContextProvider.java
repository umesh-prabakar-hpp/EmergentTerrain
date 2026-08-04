package com.emergentterrain.researchservice;

import com.emergentterrain.domain.Room;

/** Supplies game-owned research context from either the migration database path or the v1 API. */
public interface ResearchGameContextProvider {

    ResearchGameContext contextFor(Room room);
}
