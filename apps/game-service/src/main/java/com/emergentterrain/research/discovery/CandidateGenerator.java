package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b Phase 3 — produces a candidate Julia method for a spec.
 * Injected into {@link CandidateDiscoveryService} so the loop is testable with a
 * fake generator. The production impl ({@link LlmCandidateGenerator}) calls a
 * frontier model; this is where "ask the LLM to invent the method" lives.
 */
public interface CandidateGenerator {

    String generate(GenerationRequest request);

    /**
     * @param spec         what {@code candidate(x)} must compute (from the task)
     * @param previousCode the prior attempt, or null on the first try
     * @param discrepancy  the moat's reason the prior attempt failed (for repair),
     *                     or null on the first try
     */
    record GenerationRequest(String spec, String previousCode, String discrepancy) {
        public boolean isRepair() {
            return previousCode != null && !previousCode.isBlank();
        }
    }
}
