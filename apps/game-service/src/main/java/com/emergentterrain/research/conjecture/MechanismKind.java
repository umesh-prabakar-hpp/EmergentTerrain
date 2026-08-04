package com.emergentterrain.research.conjecture;

import java.util.Locale;

/**
 * Phase 30.7 — the reasoning mode a conjecture-investigation paper declares in
 * its {@code Mechanism kind:} tag. Each kind has (or will have) a verification
 * hook in {@link MechanismVerifier}; until a hook runs and VERIFIES the declared
 * mechanism, the prose cannot justify a label above {@code CONJECTURE}.
 */
public enum MechanismKind {
    HEURISTIC_DERIVATION,
    ASYMPTOTIC_INTUITION,
    PROBABILISTIC_MODEL,
    EXTREMAL_CONSTRUCTION,
    ANALOGY_TO_KNOWN_THEORY,
    NONE;

    /**
     * Parse a {@code Mechanism kind: X} value (case/space/hyphen-insensitive),
     * tolerating an optional leading {@code "mechanism kind:"} prefix. Unknown or
     * blank input maps to {@link #NONE}.
     */
    public static MechanismKind fromTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return NONE;
        }
        String norm = tag.trim().toLowerCase(Locale.ROOT);
        int kindIdx = norm.indexOf("kind:");
        if (kindIdx >= 0) {
            norm = norm.substring(kindIdx + "kind:".length()).trim();
        }
        norm = norm.replace('-', '_').replace(' ', '_');
        for (MechanismKind k : values()) {
            if (k.name().toLowerCase(Locale.ROOT).equals(norm)) {
                return k;
            }
        }
        return NONE;
    }
}
