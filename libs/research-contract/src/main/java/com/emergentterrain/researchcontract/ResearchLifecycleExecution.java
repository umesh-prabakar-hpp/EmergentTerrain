package com.emergentterrain.researchcontract;

/**
 * Game-owned execution metadata for the lifecycle portion of a research tick.
 *
 * <p>A snapshot is post-tick state, so a remote projector must know whether the legacy lifecycle
 * actually advanced at this game tick before it can compare a predicted successor. A null
 * {@code adultToElder} means no lifecycle transition ran.</p>
 */
public record ResearchLifecycleExecution(long gameTick, boolean advanced, Long adultToElder) {
}
