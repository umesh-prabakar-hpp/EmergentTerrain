package com.emergentterrain.service;

import com.emergentterrain.llm.ToolInvocation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lever 2 — the revision self-check tools must reproduce the publish-stage
 * validators' verdicts: PASS on clean prose, FAIL (with the validator's reason)
 * on prose those validators flag. Fixtures are lifted from each validator's own
 * test so the tool and the gate stay in lock-step.
 */
class RevisionSelfCheckToolsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private Function<String, String> exec(List<ToolInvocation> trace) {
        return new RevisionSelfCheckTools(M).buildExecutor(trace);
    }

    private static String call(String name, String argsJson) {
        return "{\"name\":\"" + name + "\",\"arguments\":" + argsJson + "}";
    }

    // ── direction-of-finding ──────────────────────────────────────────────

    @Test
    void directionPolarityInversionFails() {
        String args = M.createObjectNode()
                .put("hypothesis", "We expect convergence within 5% over the survey window.")
                .put("findings", "The maximum deviation was 0.694% across all evaluations.")
                .put("conclusion", "The hypothesis is therefore ruled out by the observed data.")
                .toString();
        String r = exec(List.of()).apply(call(RevisionSelfCheckTools.DIRECTION_TOOL, args));
        assertThat(r).startsWith(RevisionSelfCheckTools.DIRECTION_TOOL + ": FAIL");
    }

    @Test
    void directionConsistentPolarityPasses() {
        String args = M.createObjectNode()
                .put("hypothesis", "Predicted to land within 5% across the regime.")
                .put("findings", "Maximum deviation 0.694% observed.")
                .put("conclusion", "Hypothesis confirmed by the close agreement.")
                .toString();
        String r = exec(List.of()).apply(call(RevisionSelfCheckTools.DIRECTION_TOOL, args));
        assertThat(r).isEqualTo(RevisionSelfCheckTools.DIRECTION_TOOL + ": PASS");
    }

    // ── uncertainty-disclosure ────────────────────────────────────────────

    @Test
    void uncertaintyMissingFails() {
        String args = M.createObjectNode()
                .put("findings", "max σ_y ≈ 2377 MPa across the grain-size sweep.")
                .toString();
        String r = exec(List.of()).apply(call(RevisionSelfCheckTools.UNCERTAINTY_TOOL, args));
        assertThat(r).startsWith(RevisionSelfCheckTools.UNCERTAINTY_TOOL + ": FAIL");
    }

    @Test
    void uncertaintyDisclosedPasses() {
        String args = M.createObjectNode()
                .put("findings", "max σ_y ≈ 2377 MPa ± 12 MPa across the grain-size sweep.")
                .toString();
        String r = exec(List.of()).apply(call(RevisionSelfCheckTools.UNCERTAINTY_TOOL, args));
        assertThat(r).isEqualTo(RevisionSelfCheckTools.UNCERTAINTY_TOOL + ": PASS");
    }

    // ── single-point-sweep ────────────────────────────────────────────────

    @Test
    void inflatedSweepFailsAgainstThinTrace() {
        List<ToolInvocation> trace = List.of(
                new ToolInvocation("run_param_sweep", "{}", "{\"x\":1}", 0));
        String args = M.createObjectNode()
                .put("findings", "The Arrhenius multiplier was averaged over sixteen sampled cases.")
                .toString();
        String r = exec(trace).apply(call(RevisionSelfCheckTools.SWEEP_TOOL, args));
        assertThat(r).startsWith(RevisionSelfCheckTools.SWEEP_TOOL + ": FAIL");
    }

    @Test
    void noSampleClaimPasses() {
        String args = M.createObjectNode()
                .put("findings", "The mean multiplier was computed from the sweep output.")
                .toString();
        String r = exec(List.of(new ToolInvocation("run_param_sweep", "{}", "{}", 0)))
                .apply(call(RevisionSelfCheckTools.SWEEP_TOOL, args));
        assertThat(r).isEqualTo(RevisionSelfCheckTools.SWEEP_TOOL + ": PASS");
    }

    // ── misc ──────────────────────────────────────────────────────────────

    @Test
    void unknownToolReported() {
        String r = exec(List.of()).apply(call("check_nonsense", "{}"));
        assertThat(r).startsWith("Unknown self-check tool:");
    }

    @Test
    void toolsJsonAdvertisesAllThreeChecksAndNoEnum() {
        String json = new RevisionSelfCheckTools(M).toolsJson();
        assertThat(json)
                .contains(RevisionSelfCheckTools.DIRECTION_TOOL)
                .contains(RevisionSelfCheckTools.UNCERTAINTY_TOOL)
                .contains(RevisionSelfCheckTools.SWEEP_TOOL)
                .doesNotContain("\"enum\"");
    }
}
