package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b — one accumulated, persisted frontier result: at scale
 * {@code x}, the bound's quantity validated to {@code value} via {@code source}
 * ("sieve"/"discovery"/…) at {@link TrustLevel} {@code trust}. The persisted
 * corpus is the system's accumulating, provenance-tagged knowledge (survives
 * restart; reusable by future papers with the trust carried verbatim).
 */
public record FrontierRecord(String bound, long x, long value, String source, TrustLevel trust) {}
