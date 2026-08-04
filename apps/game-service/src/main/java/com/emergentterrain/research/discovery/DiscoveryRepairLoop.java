package com.emergentterrain.research.discovery;

import com.emergentterrain.research.discovery.CandidateGenerator.GenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Phase 30 fork 2b §10 — the SHARED generate→validate→repair loop for runtime discovery,
 * generic over the validation-result type {@code V} so EVERY domain (Mertens, proxy, future
 * bounds) runs ONE implementation. Centralised here, domain-neutral, so they can never drift:
 * <ul>
 *   <li>per-attempt candidate logging (code + sha256 — auditability);</li>
 *   <li>the recipe-free resource signal on a timeout/oom ({@link #augment}) — tells the model
 *       WHAT failed (the budget) and that it is FREE to use any technique, never the algorithm;</li>
 *   <li>early-exit after K consecutive resource failures (the scale is too big, not a bug);</li>
 *   <li>capture of the last failed code + status (for the knowledge store's boundary events).</li>
 * </ul>
 *
 * <p>A domain supplies only: the spec text, a label, the validation ({@code code -> V}), and
 * {@code accepted}/{@code statusOf}/{@code failureTextOf} accessors. This is what makes
 * "add a domain = a spec + a trusted oracle" actually true — see frontier-escalation-loop.md.
 */
public final class DiscoveryRepairLoop {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryRepairLoop.class);

    private DiscoveryRepairLoop() {}

    /** Maps a candidate's code to the domain's validation result (the moat call lives in here). */
    @FunctionalInterface
    public interface Validate<V> {
        V apply(String code);
    }

    /** The winning candidate (on accept, {@code winning} carries the validated result) or the
     *  last-failure state (for the caller to build a domain exhausted/boundary outcome). */
    public record Result<V>(boolean accepted, String code, int attempts, V winning,
                            String lastFailedCode, String lastStatus, String lastDiscrepancy) {}

    /** Sandbox statuses where the fix is a better algorithm / less memory, not a numeric bug. */
    public static boolean isResourceFailure(String status) {
        return "timeout".equals(status) || "oom".equals(status);
    }

    /**
     * Append the recipe-free, domain-neutral resource signal to a failure discrepancy: state the
     * budget that was exceeded and that the model is FREE to use any technique — NOT the algorithm
     * (the LLM is the creative engine, the moat is the verifier). Non-resource failures pass through.
     */
    public static String augment(String discrepancy, String status) {
        if ("timeout".equals(status)) {
            return discrepancy + " — TOO SLOW at this scale: it did not finish within the time budget. "
                    + "A faster constant on the same approach will not be enough — a more efficient "
                    + "algorithm is needed. You are free to use any technique; correctness is still "
                    + "checked against ground truth.";
        }
        if ("oom".equals(status)) {
            return discrepancy + " — EXCEEDED THE MEMORY BUDGET: do not allocate memory that grows "
                    + "with x. Find an approach with bounded memory; correctness is still checked.";
        }
        return discrepancy;
    }

    /**
     * Run the loop. {@code earlyExitThreshold} 0 disables early-exit (grind all attempts).
     * {@code validate} may not return null on an attempt that is neither accepted nor a clean
     * failure — callers wrap any exception into a failure-shaped {@code V}.
     */
    public static <V> Result<V> run(String spec, String label, int maxAttempts, int earlyExitThreshold,
                                    CandidateGenerator generator, Validate<V> validate,
                                    Predicate<V> accepted, Function<V, String> statusOf,
                                    Function<V, String> failureTextOf) {
        String previousCode = null;
        String discrepancy = null;
        String lastCode = null;
        String lastStatus = null;
        int consecutiveResourceFails = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String code = generator.generate(new GenerationRequest(spec, previousCode, discrepancy));
            if (code == null || code.isBlank()) {
                discrepancy = "generator returned no code";
                log.info("discovery[{}] attempt {}/{} — {}", label, attempt, maxAttempts, discrepancy);
                continue;
            }
            // Log the LLM-generated candidate verbatim (+sha) — auditable in the log file.
            log.info("discovery[{}] attempt {}/{} — candidate (sha256={}):\n{}",
                    label, attempt, maxAttempts, sha256(code), clip(code));
            V v = validate.apply(code);
            if (accepted.test(v)) {
                log.info("discovery[{}] accepted on attempt {}/{} | method sha256={}",
                        label, attempt, maxAttempts, sha256(code));
                return new Result<>(true, code, attempt, v, null, null, null);
            }
            previousCode = code;
            lastCode = code;
            lastStatus = statusOf.apply(v);
            discrepancy = augment(failureTextOf.apply(v), lastStatus);
            log.info("discovery[{}] attempt {}/{} rejected — {}", label, attempt, maxAttempts, discrepancy);

            // early-exit: repeated resource failure ⇒ the SCALE is too big, not a fixable bug.
            consecutiveResourceFails = isResourceFailure(lastStatus) ? consecutiveResourceFails + 1 : 0;
            if (earlyExitThreshold > 0 && consecutiveResourceFails >= earlyExitThreshold
                    && attempt < maxAttempts) {
                log.info("discovery[{}] early-exit — {} consecutive {}; too big, stopping after {}/{} "
                        + "(feeding back)", label, consecutiveResourceFails, lastStatus, attempt, maxAttempts);
                return new Result<>(false, null, attempt, null, lastCode, lastStatus, discrepancy);
            }
        }
        return new Result<>(false, null, maxAttempts, null, lastCode, lastStatus, discrepancy);
    }

    /** Trim a candidate for the log so a pathological generation can't flood it. */
    static String clip(String code) {
        return code.length() <= 4000 ? code : code.substring(0, 4000) + "\n… [" + (code.length() - 4000) + " more chars]";
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "nohash-" + Integer.toHexString(s.hashCode());
        }
    }
}
