package com.emergentterrain.research.discovery;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 30 frontier-escalation Phase 3 — the B4 (predictive model) proposer. When
 * computation stalls, fit a model to the escalation corpus and *extrapolate*
 * rather than compute. Concretely: model the bound's ratio {@code r(x) =
 * |value|/√x} against {@code ln(x)} by least squares, extrapolate to the stalled
 * target, and predict whether the bound still holds — tagged {@code MODEL_FIT}
 * with the fit quality (R²) and an explicit "extrapolation, not proof" caveat.
 *
 * <p>Pure (no LLM, no sidecar): the corpus is the computed {@code (x, M(x))}
 * checkpoints the escalation already produced. Honest by construction — the
 * {@code MODEL_FIT} trust and the caveat travel with the value, and for Mertens
 * the corpus sits *below* the regime where the bound is known to fail, so a
 * "holds" extrapolation is explicitly not a resolution.
 */
@Component
public class B4PredictiveModelProposer implements BranchProposer {

    @Override
    public Branch branch() {
        return Branch.PREDICTIVE_MODEL;
    }

    @Override
    public PivotOutcome attempt(PivotRequest request) {
        List<double[]> pts = new ArrayList<>();   // {ln(x), ratio}
        for (EscalationCheckpoint c : request.corpus()) {
            if (c.value() != null && c.x() > 1) {
                double ratio = Math.abs((double) c.value()) / Math.sqrt((double) c.x());
                pts.add(new double[]{Math.log((double) c.x()), ratio});
            }
        }
        if (pts.size() < 3) {
            return new PivotOutcome(Branch.PREDICTIVE_MODEL, TrustLevel.MODEL_FIT, false,
                    "insufficient data to model (" + pts.size() + " usable corpus points; need >=3)");
        }
        int n = pts.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (double[] q : pts) { sx += q[0]; sy += q[1]; sxx += q[0] * q[0]; sxy += q[0] * q[1]; }
        double meanY = sy / n;
        double denom = n * sxx - sx * sx;
        double b = denom == 0 ? 0 : (n * sxy - sx * sy) / denom;
        double a = (sy - b * sx) / n;
        double ssRes = 0, ssTot = 0;
        for (double[] q : pts) {
            double pred = a + b * q[0];
            ssRes += (q[1] - pred) * (q[1] - pred);
            ssTot += (q[1] - meanY) * (q[1] - meanY);
        }
        double r2 = ssTot == 0 ? 1.0 : 1.0 - ssRes / ssTot;
        double rAtTarget = a + b * Math.log((double) request.target());
        boolean predictedHolds = rAtTarget < 1.0;
        String finding = String.format(
                "model |M(x)|/sqrt(x) ~= %.4f %+.4f*ln(x) (R^2=%.3f, n=%d); extrapolated to x=%d gives "
                + "ratio ~= %.3f => bound predicted to %s. MODEL_FIT trust: this is an EXTRAPOLATION, "
                + "NOT a proof — and the fit data sits below the scale where this bound is known to fail.",
                a, b, r2, n, request.target(), rAtTarget, predictedHolds ? "HOLD" : "FAIL");
        return new PivotOutcome(Branch.PREDICTIVE_MODEL, TrustLevel.MODEL_FIT, true, finding);
    }
}
