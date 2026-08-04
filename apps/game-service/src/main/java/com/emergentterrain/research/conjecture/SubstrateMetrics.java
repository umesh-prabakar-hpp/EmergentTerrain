package com.emergentterrain.research.conjecture;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 30 integration (observability) — a read-only snapshot of what the
 * Phase-30 substrate sees on a conjecture pool:
 * <ul>
 *   <li>{@code survivors} — pool size;</li>
 *   <li>{@code distinctRelations} — distinct descriptor cells (so
 *       {@code survivors - distinctRelations} is the redundancy the QD archive
 *       would collapse);</li>
 *   <li>{@code novelCompositions} — sound 1-step compositions (W1-filtered,
 *       deduped) whose derived relation is not already present (the novelty
 *       composition could add).</li>
 * </ul>
 *
 * <p>Pure; logged by {@link ConjectureMinerService} each mine pass when
 * {@code app.research.conjectureMiner.substrateMetricsEnabled} is on. This is the
 * first, behavior-free integration step: it wires the substrate over the live
 * pool and reports the signal that should gate the later persisting step.
 */
public record SubstrateMetrics(int survivors, int distinctRelations, int novelCompositions) {

    public static SubstrateMetrics of(List<Inequality> pool) {
        if (pool == null || pool.isEmpty()) {
            return new SubstrateMetrics(0, 0, 0);
        }
        Set<String> relations = new LinkedHashSet<>();
        for (Inequality ineq : pool) {
            if (ineq != null) {
                relations.add(ConjectureTrivialityFilter.descriptorKey(ineq));
            }
        }
        int novel = 0;
        for (Inequality derived : ConjectureComposer.proposeNovel(pool, 0)) {
            if (!relations.contains(ConjectureTrivialityFilter.descriptorKey(derived))) {
                novel++;
            }
        }
        return new SubstrateMetrics(pool.size(), relations.size(), novel);
    }

    public String summary() {
        return String.format(
                "substrate: %d survivors -> %d distinct relations (%d redundant), %d novel compositions available",
                survivors, distinctRelations, survivors - distinctRelations, novelCompositions);
    }
}
