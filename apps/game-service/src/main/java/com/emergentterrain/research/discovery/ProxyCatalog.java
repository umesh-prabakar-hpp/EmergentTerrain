package com.emergentterrain.research.discovery;

import java.util.List;

/**
 * Phase 30 fork 2b — illustrative proxies for the generalized oracle. These show
 * the oracle validating quantities *other than* M(x); whether any is a *useful*
 * proxy that constrains the target is the open research question B3 explores — the
 * oracle just makes them verifiable.
 */
public final class ProxyCatalog {

    private ProxyCatalog() {}

    /** Σ μ(n) — M(x) itself (the trivial proxy; a sanity check that the oracle agrees with the M(x) oracle). */
    public static final ProxySpec MERTENS_SUM = new ProxySpec("mertens_sum", 1, 0, 0, 0);

    /** Σ ω(n) — the additive prime-factor-count summatory (~ x·ln ln x). */
    public static final ProxySpec OMEGA_SUM = new ProxySpec("omega_sum", 0, 1, 0, 0);

    /** Σ (Ω−ω)(n) — prime-power multiplicity excess (zero on squarefree n; tracks non-squarefree structure). */
    public static final ProxySpec OMEGA_EXCESS = new ProxySpec("omega_excess", 0, -1, 1, 0);

    public static List<ProxySpec> all() {
        return List.of(MERTENS_SUM, OMEGA_SUM, OMEGA_EXCESS);
    }
}
