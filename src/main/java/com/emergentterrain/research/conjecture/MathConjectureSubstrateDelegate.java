package com.emergentterrain.research.conjecture;

import com.emergentterrain.llm.JuliaMathClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Phase 28 §28.6 — sidecar-backed number-theory-window compute.
 *
 * <p>After the §28.6 conjecture legacy removal the Julia sidecar's
 * {@code /numbertheory/window} endpoint (via
 * {@link JuliaMathClient#numberTheoryWindow}) is the <strong>sole</strong>
 * source of per-window number-theory scalars. The in-process
 * {@code MathConjectureSubstrate} (SPF sieve + per-integer predicates) was
 * deleted, and the sidecar's sieve ceiling was raised to 2,000,000 to cover
 * {@link ConjectureVerifier#MAX_N} in full — so there is no range gap and no
 * fallback.
 *
 * <p>If the conjecture sidecar is unavailable (flag off via
 * {@code app.julia.conjecture.enabled}, or the sidecar is unreachable) this
 * delegate throws. The miner ({@code app.research.conjectureMiner.enabled}) and
 * the verifier both run inside loops that already treat a compute failure as a
 * recoverable per-cycle error.
 *
 * <p>The delegate also exposes {@link #juliaMathClient()} so
 * {@link ConjectureVerifier} can route its batched {@code /conjecture/verify}
 * call through the same client instance.
 */
@Service
public class MathConjectureSubstrateDelegate {

    private static final Logger log = LoggerFactory.getLogger(MathConjectureSubstrateDelegate.class);

    private final JuliaMathClient juliaMathClient;

    /**
     * {@code @Autowired(required=false)} so the delegate is still constructible
     * in test-slice contexts without a JuliaMathClient bean; {@link #evaluateWindow}
     * then throws a clear error rather than NPE-ing.
     */
    @Autowired
    public MathConjectureSubstrateDelegate(
            @Autowired(required = false) JuliaMathClient juliaMathClient) {
        this.juliaMathClient = juliaMathClient;
        log.info("MathConjectureSubstrateDelegate: ready (sidecar-only; client={})",
                juliaMathClient != null);
    }

    /**
     * Evaluate the per-window number-theory scalar map for
     * {@code [start, start+size)} via the sidecar.
     *
     * <p>The returned map shape (key order + value semantics) is the sidecar's
     * {@code /numbertheory/window} response — the contract the
     * {@code substrate.py} miner and {@link MathInvariants} read.
     *
     * @throws IllegalStateException if the conjecture sidecar is unavailable or
     *         the request fails — there is no in-process fallback after the
     *         §28.6 legacy removal.
     */
    public Map<String, Double> evaluateWindow(int start, int size) {
        if (juliaMathClient == null || !juliaMathClient.isConjectureAvailable()) {
            throw new IllegalStateException(
                    "number-theory-window compute requires the Julia conjecture sidecar, which is "
                            + "unavailable (start=" + start + ", size=" + size + "). Set "
                            + "app.julia.conjecture.enabled=true and ensure the sidecar is reachable. "
                            + "The in-process MathConjectureSubstrate was removed in phase-28 §28.6.");
        }
        try {
            return juliaMathClient.numberTheoryWindow(start, size).scalars();
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "sidecar /numbertheory/window failed (start=" + start + ", size=" + size
                            + "): " + e.getMessage(), e);
        }
    }

    /**
     * Phase 28 §28.4 — accessor for the wrapped sidecar client.
     * {@link ConjectureVerifier} reads this to route /conjecture/verify
     * through the same JuliaMathClient instance. Returns null when no
     * sidecar is wired.
     */
    public JuliaMathClient juliaMathClient() {
        return juliaMathClient;
    }
}
