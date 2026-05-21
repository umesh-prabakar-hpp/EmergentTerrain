package com.emergentterrain.research.conjecture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-curated map from our math-domain scalar names (the ones in
 * {@link MathInvariants#BASE} and selected {@link MathInvariants#DERIVED}
 * entries) to canonical OEIS A-numbers.
 *
 * <p>The mapping is the load-bearing piece of Phase 23.2: it makes
 * conjecture-driven papers citable against OEIS without requiring the
 * paper-writing LLM to guess A-numbers. A-number references and names
 * were verified against oeis.org at curation time (2026-05-21); use the
 * paired live-verification admin endpoint to re-check after edits.
 *
 * <p>Order matters only for stable JSON output — {@link LinkedHashMap}
 * keeps insertion order so the generated {@code oeisMatchJson} is
 * deterministic across mining passes.
 */
public final class OeisCatalog {

    private static final Map<String, List<OeisEntry>> MAP = buildMap();

    private OeisCatalog() {}

    private static Map<String, List<OeisEntry>> buildMap() {
        Map<String, List<OeisEntry>> m = new LinkedHashMap<>();

        // BASE scalars
        m.put("abundant_density",
                List.of(new OeisEntry("A005101", "Abundant numbers")));
        m.put("deficient_density",
                List.of(new OeisEntry("A005100", "Deficient numbers")));
        m.put("prime_density",
                List.of(new OeisEntry("A000040", "The prime numbers")));
        m.put("happy_density",
                List.of(new OeisEntry("A007770", "Happy numbers")));
        m.put("mean_omega",
                List.of(new OeisEntry("A001221", "omega(n): number of distinct primes dividing n")));
        m.put("mean_Omega",
                List.of(new OeisEntry("A001222", "bigomega(n): number of prime divisors of n counted with multiplicity")));
        m.put("mean_divisor_count",
                List.of(new OeisEntry("A000005", "d(n) (also tau, sigma_0): number of divisors of n")));
        m.put("max_divisor_count",
                List.of(new OeisEntry("A000005", "d(n) (also tau, sigma_0): number of divisors of n")));
        m.put("mertens_delta",
                List.of(new OeisEntry("A002321", "Mertens function: Sum_{k=1..n} mu(k)")));

        // DERIVED scalars with a direct OEIS analog (most don't)
        m.put("omega_Omega_gap",
                List.of(new OeisEntry("A046660", "Excess of n: bigomega(n) - omega(n)")));

        return Map.copyOf(m);
    }

    /** A-numbers for a scalar name; empty list if no canonical reference exists. */
    public static List<OeisEntry> lookup(String scalarName) {
        return MAP.getOrDefault(scalarName, List.of());
    }

    /** All A-numbers referenced by the catalog, deduped. Useful for the live-verify admin endpoint. */
    public static List<String> allReferencedAnumbers() {
        return MAP.values().stream()
                .flatMap(List::stream)
                .map(OeisEntry::id)
                .distinct()
                .sorted()
                .toList();
    }

    /** Number of mapped scalar names. */
    public static int size() {
        return MAP.size();
    }
}
