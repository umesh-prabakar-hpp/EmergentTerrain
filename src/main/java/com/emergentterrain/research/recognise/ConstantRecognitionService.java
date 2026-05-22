package com.emergentterrain.research.recognise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 26 §26.4 — orchestrator that fans recognise() calls across
 * the OEIS / PSLQ / LLL clients. Returns a unified
 * {@link RecognitionResult} sum type, so the narration layer doesn't
 * have to know which engine produced the hit.
 *
 * <h2>Dispatch matrix</h2>
 *
 * <table>
 *   <tr><th>Input shape</th><th>Engine</th><th>Fall-through</th></tr>
 *   <tr><td>integer sequence (≥4 terms)</td>
 *       <td>{@link OeisRecogniserClient}</td>
 *       <td>—</td></tr>
 *   <tr><td>real decimal string</td>
 *       <td>{@link PslqRecogniserClient}</td>
 *       <td>{@link LllRecogniserClient} when PSLQ misses AND
 *           {@code app.julia.recognise.lll.enabled} is on</td></tr>
 * </table>
 *
 * <h2>Failure surfaces</h2>
 *
 * Engine transport failures (sidecar down, OEIS rate-limited, etc.)
 * log at WARN and return {@link RecognitionResult.NoMatch}. The
 * narration layer should treat NoMatch as "cite the naked
 * decimal" — recognition is best-effort and a sidecar outage must
 * not block paper publication.
 *
 * <h2>Optional dependencies</h2>
 *
 * All three engine clients are {@code @Autowired(required = false)}
 * so tests and configurations without the recognise stack still
 * construct cleanly. When any client is null (or its
 * {@code isAvailable()} is false), the orchestrator silently skips
 * that arm and either returns NoMatch or falls through to the next.
 */
@Service
public class ConstantRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(ConstantRecognitionService.class);

    private final OeisRecogniserClient oeisClient;
    private final PslqRecogniserClient pslqClient;
    private final LllRecogniserClient  lllClient;

    private final AtomicLong sequenceCalls   = new AtomicLong();
    private final AtomicLong constantCalls   = new AtomicLong();
    private final AtomicLong primaryHits     = new AtomicLong();
    private final AtomicLong noMatches       = new AtomicLong();

    @Autowired
    public ConstantRecognitionService(
            @Autowired(required = false) OeisRecogniserClient oeisClient,
            @Autowired(required = false) PslqRecogniserClient pslqClient,
            @Autowired(required = false) LllRecogniserClient  lllClient) {
        this.oeisClient = oeisClient;
        this.pslqClient = pslqClient;
        this.lllClient  = lllClient;
    }

    /**
     * Try to recognise an integer sequence via OEIS Superseeker.
     *
     * @param sequence the integer sequence; must have ≥4 terms
     *                 (Superseeker disambiguation floor)
     * @param context  caller-supplied free-form string for the audit
     *                 trail (paper id, computation step, etc.)
     */
    public RecognitionResult recogniseSequence(int[] sequence, String context) {
        sequenceCalls.incrementAndGet();
        if (oeisClient == null || !oeisClient.isAvailable()) {
            return tally(RecognitionResult.NoMatch.INSTANCE);
        }
        try {
            Optional<OeisRecogniserClient.OeisHit> hit = oeisClient.lookup(sequence);
            if (hit.isPresent()) {
                OeisRecogniserClient.OeisHit h = hit.get();
                RecognitionEvidence evidence = new RecognitionEvidence(
                        RecognitionEvidence.ENGINE_OEIS,
                        h.name(),
                        "cite-recognised:oeis:" + h.oeisId(),
                        h.oeisId() + ":" + h.transformChain(),
                        // OEIS calls don't surface elapsed_ms through
                        // the client today; use 0 as placeholder.
                        0L);
                return tally(new RecognitionResult.PrimaryHit(evidence));
            }
            return tally(RecognitionResult.NoMatch.INSTANCE);
        } catch (IllegalArgumentException e) {
            log.warn("recogniseSequence rejected by validator (context={}): {}",
                    context, e.getMessage());
            return tally(RecognitionResult.NoMatch.INSTANCE);
        } catch (OeisRecogniserException e) {
            log.warn("recogniseSequence transport failure (context={}, code={}): {}",
                    context, e.errorCode(), e.getMessage());
            return tally(RecognitionResult.NoMatch.INSTANCE);
        }
    }

    /**
     * Try to recognise a real constant via PSLQ, falling through to
     * the LLL minpoly recogniser when PSLQ misses AND the LLL
     * sub-flag is on.
     *
     * @param value   decimal string of the constant to recognise
     * @param basis   basis name for PSLQ ({@code "BASIC"} or
     *                {@code "PHYSICS"})
     * @param context caller-supplied free-form audit-trail string
     */
    public RecognitionResult recogniseConstant(String value, String basis, String context) {
        constantCalls.incrementAndGet();

        if (pslqClient != null && pslqClient.isAvailable()) {
            try {
                Optional<PslqRecogniserClient.PslqHit> hit = pslqClient.recognise(value, basis);
                if (hit.isPresent()) {
                    PslqRecogniserClient.PslqHit h = hit.get();
                    String handle = "cite-recognised:pslq:" + sanitiseHandle(h.name());
                    RecognitionEvidence evidence = new RecognitionEvidence(
                            RecognitionEvidence.ENGINE_PSLQ,
                            h.name(),
                            handle,
                            "basis=" + h.basisUsed() + ";precision=" + h.precisionUsed()
                                    + ";relation=" + renderRelation(h.relation()),
                            // PSLQ client doesn't expose sidecar elapsedMs today; reserved.
                            0L);
                    return tally(new RecognitionResult.PrimaryHit(evidence));
                }
            } catch (IllegalArgumentException e) {
                log.warn("recogniseConstant PSLQ rejected by validator " +
                        "(context={}): {}", context, e.getMessage());
                return tally(RecognitionResult.NoMatch.INSTANCE);
            } catch (PslqRecogniserException e) {
                log.warn("recogniseConstant PSLQ transport failure " +
                        "(context={}, code={}): {}", context, e.errorCode(), e.getMessage());
                // Transport failure on the PSLQ path likely indicates the
                // sidecar is degraded — don't bother trying LLL on the
                // same sidecar.
                return tally(RecognitionResult.NoMatch.INSTANCE);
            }
        }

        // PSLQ missed (or wasn't available). Try LLL minimal-polynomial
        // recovery if the sub-flag is on.
        if (lllClient != null && lllClient.isAvailable()) {
            try {
                Optional<LllRecogniserClient.MinPolyHit> hit = lllClient.recognise(value);
                if (hit.isPresent()) {
                    LllRecogniserClient.MinPolyHit h = hit.get();
                    String handle = "cite-recognised:lll:degree" + h.degree() + ":"
                            + sanitiseHandle(h.minPoly());
                    RecognitionEvidence evidence = new RecognitionEvidence(
                            RecognitionEvidence.ENGINE_LLL,
                            h.minPoly(),
                            handle,
                            "degree=" + h.degree() + ";precision=" + h.precisionUsed()
                                    + ";coefficients=" + h.coefficients().toString(),
                            0L);
                    return tally(new RecognitionResult.PrimaryHit(evidence));
                }
            } catch (IllegalArgumentException e) {
                log.warn("recogniseConstant LLL rejected by validator " +
                        "(context={}): {}", context, e.getMessage());
            } catch (LllRecogniserException e) {
                log.warn("recogniseConstant LLL transport failure " +
                        "(context={}, code={}): {}", context, e.errorCode(), e.getMessage());
            }
        }

        return tally(RecognitionResult.NoMatch.INSTANCE);
    }

    /**
     * Sanitise an engine-rendered name into a citation-handle-safe
     * token. Replaces whitespace and symbols that interfere with
     * the validator's tokenizer; keeps the result short by
     * truncating at 60 chars.
     */
    private static String sanitiseHandle(String name) {
        if (name == null) return "";
        // Collapse whitespace → underscore; drop characters outside
        // [A-Za-z0-9_+\-^()*/.] so the handle is a single token.
        String collapsed = name.replaceAll("\\s+", "_")
                .replaceAll("[^A-Za-z0-9_+\\-^()*/.]", "");
        return collapsed.length() <= 60 ? collapsed : collapsed.substring(0, 60);
    }

    /** Render a PSLQ relation as a short evidence string for the audit row. */
    private static String renderRelation(
            java.util.List<PslqRecogniserClient.RelationTerm> relation) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < relation.size(); i++) {
            if (i > 0) sb.append(',');
            PslqRecogniserClient.RelationTerm t = relation.get(i);
            sb.append(t.coefficient()).append('*').append(t.basisName());
        }
        sb.append(']');
        return sb.toString();
    }

    private RecognitionResult tally(RecognitionResult result) {
        if (result instanceof RecognitionResult.PrimaryHit) {
            primaryHits.incrementAndGet();
        } else if (result instanceof RecognitionResult.NoMatch) {
            noMatches.incrementAndGet();
        }
        return result;
    }

    /** Snapshot of counters at call time. */
    public Stats stats() {
        return new Stats(
                sequenceCalls.get(),
                constantCalls.get(),
                primaryHits.get(),
                noMatches.get());
    }

    public record Stats(
            long sequenceCalls,
            long constantCalls,
            long primaryHits,
            long noMatches) {}
}
