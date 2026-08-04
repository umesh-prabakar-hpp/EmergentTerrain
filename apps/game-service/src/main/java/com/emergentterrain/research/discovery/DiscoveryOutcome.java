package com.emergentterrain.research.discovery;

/**
 * Phase 30 fork 2b Phase 3 — the result of a discovery run. On success it
 * carries the accepted candidate, its validated target value, and
 * {@link Provenance} (so a downstream paper can present the number as
 * "computed by a moat-validated method", never as an established result — see
 * runtime-self-verifying-execution.md §6).
 */
public record DiscoveryOutcome(
        boolean accepted,
        String code,
        Long targetValue,
        int attempts,
        Provenance provenance,
        String failureReason,
        long elapsedMs,
        String failedCode,
        String failureStatus) {

    /**
     * What the accepted value is grounded in: the method's content hash and the
     * checkpoints it reproduced. Never asserts mathematical novelty.
     */
    public record Provenance(String codeSha256, int checkpointCount, int anchorCount, long target) {}

    public static DiscoveryOutcome disabled() {
        return new DiscoveryOutcome(false, null, null, 0, null,
                "discovery disabled (runtimeExecEnabled=false)", 0, null, "disabled");
    }

    /** Pre-flight skip: the sidecar endpoint is 403/unreachable — no candidate was generated. */
    public static DiscoveryOutcome unavailable() {
        return new DiscoveryOutcome(false, null, null, 0, null,
                "discovery endpoint unavailable (sidecar /discover/validate 403/unreachable; set ET_DISCOVER_EXEC=1)",
                0, null, "unavailable");
    }

    public static DiscoveryOutcome accepted(String code, Long targetValue, int attempts, Provenance p, long elapsedMs) {
        return new DiscoveryOutcome(true, code, targetValue, attempts, p, null, elapsedMs, null, null);
    }

    /**
     * No candidate validated. {@code failedCode}/{@code failureStatus} carry the last
     * rejected attempt — fed to the knowledge store's failure events and the paper's
     * computational-boundary section. {@code failureStatus} is the sandbox status
     * (e.g. {@code oom}, {@code timeout}, {@code wrong}).
     */
    public static DiscoveryOutcome exhausted(int attempts, String lastReason, String failedCode, String failureStatus) {
        return new DiscoveryOutcome(false, null, null, attempts, null,
                "no candidate validated within " + attempts + " attempt(s); last: " + lastReason, 0,
                failedCode, failureStatus);
    }

    /** Back-compatible exhausted (no captured code/status). */
    public static DiscoveryOutcome exhausted(int attempts, String lastReason) {
        return exhausted(attempts, lastReason, null, null);
    }
}
