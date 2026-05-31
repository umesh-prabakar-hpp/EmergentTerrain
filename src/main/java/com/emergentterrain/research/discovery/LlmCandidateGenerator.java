package com.emergentterrain.research.discovery;

import com.emergentterrain.service.LlmRouter;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 30 fork 2b Phase 3 — production {@link CandidateGenerator}: asks a
 * frontier model (via {@link LlmRouter#callPrimary}) for a candidate Julia
 * method and extracts the code from its reply. This is the "ask the LLM to
 * invent the method" step; correctness is not assumed — the moat
 * ({@link CandidateValidator}) decides, and the loop repairs.
 *
 * <p>Domain-neutral: the spec comes from the task. The contract (signature,
 * purity, no-IO) is fixed so the sandbox AST allowlist and the
 * candidate-emits/judge contract are satisfied.
 */
@Component
public class LlmCandidateGenerator implements CandidateGenerator {

    /** The fixed contract every candidate must satisfy (matches the sandbox + driver). */
    static final String CONTRACT = """
            Write a single Julia function with EXACTLY this signature:
                candidate(x::Integer)::Int64
            Constraints:
            - Pure base Julia only: NO `using`/`import`, NO `eval`/`ccall`/`unsafe_*`, NO I/O
              (`open`/`read`/`write`/`run`/`download`), NO tasks/threads. Base math (isqrt, div,
              cld, arrays) is fine.
            - Deterministic; return Int64; return 0 for x < 1.
            - It will be run in an isolated sandbox and checked against ground truth.
            Output ONLY the Julia code in a single ```julia code fence — the function plus any
            helpers it needs. No prose, no comments explaining your reasoning.""";

    private static final Pattern FENCE =
            Pattern.compile("```(?:julia|jl)?\\s*\\n(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private final LlmRouter router;

    public LlmCandidateGenerator(LlmRouter router) {
        this.router = router;
    }

    @Override
    public String generate(GenerationRequest request) {
        String prompt = request.isRepair() ? repairPrompt(request) : firstPrompt(request);
        String reply = router.callPrimary(prompt);
        return extractJulia(reply);
    }

    private static String firstPrompt(GenerationRequest r) {
        return "Implement, in Julia, a method that computes: " + r.spec() + "\n\n" + CONTRACT;
    }

    private static String repairPrompt(GenerationRequest r) {
        return "Your previous Julia attempt to compute: " + r.spec() + "\n"
                + "is WRONG. An independent validator found:\n    " + r.discrepancy() + "\n\n"
                + "Find and fix the bug. Here is your previous attempt:\n\n```julia\n"
                + r.previousCode() + "\n```\n\n"
                + "Output ONLY the corrected Julia function in a single ```julia code fence. "
                + "No prose, no explanation of what you changed.";
    }

    /**
     * Extract the Julia source from a model reply: the first fenced block if
     * present, else the whole reply trimmed (a model that returned bare code).
     * Package-private and static for unit testing.
     */
    static String extractJulia(String reply) {
        if (reply == null) {
            return "";
        }
        Matcher m = FENCE.matcher(reply);
        if (m.find()) {
            return m.group(1).strip();
        }
        return reply.strip();
    }
}
