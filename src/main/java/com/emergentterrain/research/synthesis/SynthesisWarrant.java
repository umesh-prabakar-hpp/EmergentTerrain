package com.emergentterrain.research.synthesis;

import com.emergentterrain.research.synthesis.SynthesisComposer.SourcePaper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 31 §31.7 — the warrant gate: decides WHEN a topic family is ripe for a
 * synthesis paper, so the scheduler doesn't compose over thin or one-sided
 * evidence. Pure, stateless; the service feeds it the domain's recent
 * cite-able papers.
 *
 * <p>A family is warranted iff it has ≥ {@code minSources} distinct
 * publishable papers (the cite-able corpus) AND those papers aren't a single
 * one-line repeat — synthesis needs MULTIPLE angles to be worth writing. The
 * canonical example is the cramer convergence: six papers, six dressings of
 * one ceiling — exactly the shape a synthesis exists to assemble.
 */
public final class SynthesisWarrant {

    private SynthesisWarrant() {}

    /** A warranted family and its cite-able sources. */
    public record Warrant(String family, List<SourcePaper> sources) {}

    /**
     * Group the cite-able papers by family (topic) and return the families
     * that clear {@code minSources}, strongest-corpus first (most sources).
     * "Cite-able" = the caller already filtered to PUBLISHED/cite-worthy
     * papers; this only groups and thresholds.
     */
    public static List<Warrant> warranted(List<SourcePaper> citeable, int minSources) {
        Map<String, List<SourcePaper>> byFamily = new LinkedHashMap<>();
        if (citeable != null) {
            for (SourcePaper p : citeable) {
                if (p == null || p.topic() == null) continue;
                byFamily.computeIfAbsent(p.topic(), k -> new java.util.ArrayList<>()).add(p);
            }
        }
        List<Warrant> out = new java.util.ArrayList<>();
        for (Map.Entry<String, List<SourcePaper>> e : byFamily.entrySet()) {
            if (e.getValue().size() >= Math.max(2, minSources)) {
                out.add(new Warrant(e.getKey(), List.copyOf(e.getValue())));
            }
        }
        out.sort((a, b) -> Integer.compare(b.sources().size(), a.sources().size()));
        return out;
    }
}
