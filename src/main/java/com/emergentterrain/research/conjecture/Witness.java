package com.emergentterrain.research.conjecture;

/**
 * Record of a tick that refuted an inequality. {@code runKey} identifies
 * the run (e.g. {@code "W500_low"}), {@code tick} is the within-run sample
 * index that violated the inequality, and {@code margin} is by how much
 * (positive = violation).
 *
 * <p>Mirrors {@code scripts/graffiti/conjectures.py:Witness}.
 */
public record Witness(String runKey, long tick, double margin) {
}
