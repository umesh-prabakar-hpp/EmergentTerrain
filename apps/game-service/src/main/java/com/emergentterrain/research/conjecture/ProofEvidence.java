package com.emergentterrain.research.conjecture;

/**
 * Phase 25 §25.4 — structured record of a machine-certified proof,
 * captured when the {@code prove_identity} tool returns
 * {@code status="proved"}. Lives alongside the conjecture machinery
 * because it's the proof-side complement to {@link Witness} and
 * {@link Inequality}: where those are "the conjecture survived" or
 * "the conjecture refutes here," this is "the conjecture has been
 * mechanically proven."
 *
 * <h2>How it's used</h2>
 *
 * v1 (this slice): primarily a test-fixture and audit-record type.
 * {@link com.emergentterrain.research.LabelFloor} currently reads the
 * paper's text body for the {@code prove_identity}+{@code **PROVED**}
 * marker combination — see {@code hasMachineCertifiedProof} there. The
 * record exists so a future LabelFloor overload can take a
 * {@code List<ProofEvidence>} alongside the paper for stronger
 * verification.
 *
 * <p>v2 (§25.5 acceptance work): the offline re-verifier will iterate
 * a paper's ProofEvidence list and confirm {@code s(k+1) − s(k) = F(k)}
 * holds for each Gosper certificate, or that the WZ recurrence holds
 * for each {@code zeilberger_wz} certificate. Any failure here is a
 * P0 incident — it means a THEOREM label was granted on a certificate
 * the audit can't reproduce.
 *
 * @param certificate The SymPy-rendered certificate string. For
 *                    Gosper: the antidifference T(k) such that
 *                    T(k+1) − T(k) = F(k). For Zeilberger-WZ: a
 *                    reduction-chain string identifying the
 *                    {@code sympy.simplify(sympy.summation(...))}
 *                    invocation that produced the closed form.
 * @param algorithm   Which algorithm produced the certificate
 *                    ({@code "gosper"}, {@code "zeilberger_wz"},
 *                    {@code "sister_celine"}).
 * @param summand     The original summand F(k) as a SymPy expression
 *                    string. Preserved verbatim so re-verification
 *                    can re-parse with the same locals.
 * @param var         The summation variable name (e.g. {@code "k"}).
 * @param lower       The lower bound expression as a string.
 * @param upper       The upper bound expression as a string.
 * @param result      The closed form the tool returned.
 * @param wallMs      Sidecar-reported wall-clock cost. Useful for the
 *                    in-corpus uptake check in §25.5 to confirm proofs
 *                    aren't slipping past their soft latency budget.
 */
public record ProofEvidence(
        String certificate,
        String algorithm,
        String summand,
        String var,
        String lower,
        String upper,
        String result,
        long wallMs) {
}
