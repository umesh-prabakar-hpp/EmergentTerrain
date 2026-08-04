package com.emergentterrain.research.conjecture;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 30 Wave 6 — RAF-closure reporting (sim #1): report the irreducible derivation core (iRAF) as
 * the publishable unit, and surface findings grounded in nobody's RAF.
 *
 * <p>Builds the finding-as-reaction {@link DerivationGraph} ({@code rhs → lhs}) over a conjecture pool
 * and runs the {@link DerivationClosure} math: the <b>maxRAF</b> is the food-grounded self-supporting
 * set; a target's <b>iRAF</b> is its minimal load-bearing support (delete any member and the target
 * ungrounds) — a constructive irreducibility proof and the natural publishable unit ("one closed law
 * replaces dozens of enumerated edges"). Findings outside the maxRAF are grounded in no RAF (sim #1's
 * "flag for retirement"); this reporter surfaces the count rather than retiring (observability).
 *
 * <p>Pure, deterministic. With {@code food} = the directly-computable observable vocabulary (base +
 * derived + admitted), a direct bound {@code lhs ≤ k·rhs} whose rhs is an observable is trivially
 * grounded with iRAF = {itself}; non-trivial iRAFs appear only once findings <em>produce</em> scalars
 * that aren't food (composed conjectures, vocabulary-expansion scalars) — so this strengthens as those
 * chains grow, the same maturation as clade-value selection.
 */
public final class RafReport {

    private RafReport() {
    }

    /** A target finding's irreducible-core size (number of load-bearing findings, incl. itself). */
    public record Core(String target, int size) {
    }

    /**
     * @param findings   total nodes in the derivation graph
     * @param maxRafSize food-grounded self-supporting findings
     * @param ungrounded findings in no RAF (grounded in nobody) — sim #1 retirement candidates
     * @param cores      iRAF size per requested target (empty cores omitted)
     */
    public record Report(int findings, int maxRafSize, int ungrounded, List<Core> cores) {
    }

    public static Report of(List<Inequality> conjectures, Set<String> food, List<Inequality> targets) {
        DerivationGraph g = DerivationGraph.fromConjectures(conjectures);
        Set<String> maxRaf = DerivationClosure.maxRAF(g, food);
        int findings = g.size();
        int ungrounded = Math.max(0, findings - maxRaf.size());
        List<Core> cores = new ArrayList<>();
        if (targets != null) {
            for (Inequality t : targets) {
                if (t == null) {
                    continue;
                }
                String id = ConjectureTrivialityFilter.descriptorKey(t);
                Set<String> core = DerivationClosure.irreducibleCore(g, food, id);
                if (!core.isEmpty()) {
                    cores.add(new Core(id, core.size()));
                }
            }
        }
        return new Report(findings, maxRaf.size(), ungrounded, cores);
    }
}
