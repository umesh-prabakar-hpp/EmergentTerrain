package com.emergentterrain.research.discovery;

/**
 * Phase 30 frontier-escalation Phase 1 — the classification of a bound at one
 * escalated scale.
 * <ul>
 *   <li>{@code HOLDS} — the value satisfies the bound; escalate further.</li>
 *   <li>{@code COUNTEREXAMPLE} — the value violates the bound; the real result, stop.</li>
 *   <li>{@code CANNOT_COMPUTE} — no value was obtained (sieve gap, or discovery
 *       failed/disabled) at this scale; the loop stalls here.</li>
 * </ul>
 */
public enum EscalationVerdict {
    HOLDS,
    COUNTEREXAMPLE,
    CANNOT_COMPUTE
}
