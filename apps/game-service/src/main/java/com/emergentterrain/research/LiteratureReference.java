package com.emergentterrain.research;

/**
 * One curated reference per (domain, topic). Loaded from
 * {@code literature-corpus.yml} by {@link LiteratureSearchService} at
 * startup. See {@code phase-6-piece-1-memo.md} §2.2 for the locked
 * record shape.
 *
 * <p>Four of the six string fields ({@link #citation}, {@link #closedForm},
 * {@link #validRegion}, {@link #magnitudeEstimate}) are prompt-visible — the
 * D1.3 integration will splice them into a {@code literatureContext} block.
 * {@link #notes} is operator-only commentary and must never reach the LLM.
 * {@link #priority} breaks ties when multiple entries share a (domain, topic)
 * key — higher wins, then alphabetical citation.
 *
 * <p>Phase 6 D1.5 (L2) — three optional structured fields drive the magnitude
 * validator's corpus-fallback path. Together they let the YAML express a
 * machine-readable upper bound for topics whose registry stub has none. All
 * three must be populated for the bound to be applied; any null falls back to
 * the registry-only behaviour.
 *
 * @param domain             paper domain — MATHEMATICS / PHYSICS / MATERIALS / INTERDISCIPLINARY
 * @param topic              topic name; matches {@code MathPhysicsEngine.topicName(...)}
 * @param citation           the cited author + year + work
 * @param closedForm         the textbook closed-form expression in plain text
 * @param validRegion        where the closed-form holds and where it breaks down
 * @param magnitudeEstimate  expected magnitude range the closed-form predicts (prose)
 * @param notes              operator-only commentary; never spliced into prompts
 * @param priority           selection rank when multiple entries share a key; default 0
 * @param magnitudeMax       optional structured upper bound for the validator (nullable)
 * @param magnitudeUnit      optional unit string for the structured bound (nullable)
 * @param magnitudeRegex     optional regex pattern; group 1 captures the value (nullable)
 */
public record LiteratureReference(
        String domain,
        String topic,
        String citation,
        String closedForm,
        String validRegion,
        String magnitudeEstimate,
        String notes,
        int    priority,
        Double magnitudeMax,
        String magnitudeUnit,
        String magnitudeRegex) {

    /** Backwards-compatible 8-arg constructor — no structured magnitude bound. */
    public LiteratureReference(String domain, String topic, String citation, String closedForm,
                               String validRegion, String magnitudeEstimate, String notes,
                               int priority) {
        this(domain, topic, citation, closedForm, validRegion, magnitudeEstimate, notes, priority,
                null, null, null);
    }

    /** True when the reference is a placeholder for an engine-constructed bridge with no real-world literature. */
    public boolean isEngineConstructed() {
        return citation != null && citation.contains("engine-constructed bridge");
    }

    /** True when the reference carries a machine-readable magnitude bound the validator can apply. */
    public boolean hasStructuredMagnitudeBound() {
        return magnitudeMax != null
                && magnitudeUnit != null && !magnitudeUnit.isBlank()
                && magnitudeRegex != null && !magnitudeRegex.isBlank();
    }
}
