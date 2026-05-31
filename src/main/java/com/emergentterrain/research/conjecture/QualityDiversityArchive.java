package com.emergentterrain.research.conjecture;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Phase 30 Wave 3 (phases-for-human-like-performance/phase-30-conjecture/
 * IMPLEMENTATION-ORDER.md) — quality-diversity archive (the external-frameworks
 * pattern #1). Keeps exactly <b>one elite per behavioral cell</b>: items are
 * binned by a {@code cellKey} descriptor, and within a cell only the highest
 * {@code quality} item is retained. Diverse cells coexist; near-duplicate
 * variants collapse to their best representative — the mechanism that stops a
 * flood of near-identical findings from being kept as many separate entries
 * (cf. conjecture-engine-triviality §1, and the Lenia 212-duplicate anti-pattern
 * in phase-30-simulation-patterns #11).
 *
 * <p>Generic, pure, deterministic (first-occurrence cell ordering preserved;
 * ties keep the incumbent). Not yet wired into the live pipeline — later waves
 * feed it (e.g. the miner keeps diverse elites; anomaly/residual/composition
 * findings each bin by their own descriptor).
 *
 * @param <T> the archived item type
 */
public final class QualityDiversityArchive<T> {

    private final Function<T, String> cellKey;
    /** Higher = better: {@code quality.compare(candidate, incumbent) > 0} replaces. */
    private final Comparator<T> quality;
    private final Map<String, T> elites = new LinkedHashMap<>();

    public QualityDiversityArchive(Function<T, String> cellKey, Comparator<T> quality) {
        this.cellKey = cellKey;
        this.quality = quality;
    }

    /**
     * Offer an item. Becomes the cell's elite if the cell is empty or the item
     * strictly out-qualifies the incumbent. Returns true iff it is now the elite.
     */
    public boolean offer(T item) {
        if (item == null) {
            return false;
        }
        String cell = cellKey.apply(item);
        T incumbent = elites.get(cell);
        if (incumbent == null || quality.compare(item, incumbent) > 0) {
            elites.put(cell, item);
            return true;
        }
        return false;
    }

    /** Offer all; returns the number that became (or replaced) an elite. */
    public int offerAll(Collection<? extends T> items) {
        int admitted = 0;
        if (items != null) {
            for (T item : items) {
                if (offer(item)) {
                    admitted++;
                }
            }
        }
        return admitted;
    }

    public Optional<T> eliteFor(String cell) {
        return Optional.ofNullable(elites.get(cell));
    }

    /** The current elites, one per occupied cell, in first-occupied order. */
    public Collection<T> elites() {
        return List.copyOf(elites.values());
    }

    public Set<String> cells() {
        return Set.copyOf(elites.keySet());
    }

    public int cellCount() {
        return elites.size();
    }

    // ---- ready-made bindings ----

    /**
     * Conjecture QD archive: one elite per relation
     * ({@link ConjectureTrivialityFilter#descriptorKey} cell), keeping the
     * most-confirmed re-mine. Collapses repeated mines of the same bound to a
     * single best representative; distinct relations stay in distinct cells.
     * (Confirmed-run count is the unambiguous quality signal; a slack tiebreak
     * is a later refinement.)
     */
    public static QualityDiversityArchive<Inequality> forConjectures() {
        return new QualityDiversityArchive<>(
                ConjectureTrivialityFilter::descriptorKey,
                Comparator.comparingInt(Inequality::confirmedRuns));
    }
}
