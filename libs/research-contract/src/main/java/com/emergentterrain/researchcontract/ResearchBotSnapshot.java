package com.emergentterrain.researchcontract;

import java.util.UUID;

/** Immutable game-owned bot input needed to select research work. */
public record ResearchBotSnapshot(UUID botId, String type, String lifecycleJson) {
}
