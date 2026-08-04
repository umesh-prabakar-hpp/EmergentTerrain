package com.emergentterrain.research;

import com.emergentterrain.repo.FormalFindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 4 Iter 9 — read + persist side of the formal-knowledge substrate.
 *
 * Persists extracted findings from a paper's narration, exposes the most
 * useful query shapes (by variable / by domain / by paper), and detects
 * contradictions between findings that share variables but disagree on
 * the relationship.
 *
 * Gated by {@code app.research.findings.enabled} (default false). When the
 * flag is off, {@link #persistFindings} is a no-op and no rows are written.
 * Read-side queries remain available so dashboards can be tested against
 * a pre-seeded database without needing the flag on.
 *
 * Phase 4b — theory composition ({@code A~f(B) ∧ B~g(C) → A~h(C)}) is now
 * implemented in {@link #maybeComposeTheories}. Derived hypotheses are emitted
 * as {@link ProgramOpenQuestion}s, not as new {@link FormalFinding}s — the
 * simulation, not the LLM, verifies claims (the next bot to test the
 * derived hypothesis produces the actual finding).
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** Two findings on the same variables disagree when their RelationTypes flip direction. */
    public record ContradictionPair(FormalFinding a, FormalFinding b) {}

    /** Phase 4b — minimum confidence on both input findings for composition to fire. */
    private static final double COMPOSITION_MIN_CONFIDENCE = 0.5;

    /** Phase 4b — max derived questions emitted per maybeComposeTheories call. */
    private static final int COMPOSITION_EMISSION_CAP = 5;

    private final boolean enabled;
    private final boolean theoryCompositionEnabled;
    private final FormalFindingRepository findings;

    /**
     * Phase 4b — set of (paperIdA, paperIdB) pairs whose composition has already
     * been emitted. In-memory only — same trade-off as the other Phase 4b caches.
     * Resets on restart; a re-emit after restart is acceptable since the
     * underlying recordOpenQuestion path tolerates duplicates.
     */
    private final java.util.Set<String> composedPairs =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Phase 4b — wired via setter (avoids constructor cycle with ResearchProgramService). */
    private volatile ResearchProgramService researchProgramService;

    public KnowledgeBaseService(
            @Value("${app.research.findings.enabled:false}") boolean enabled,
            @Value("${app.research.theory-composition.enabled:false}") boolean theoryCompositionEnabled,
            FormalFindingRepository findings) {
        this.enabled                  = enabled;
        this.theoryCompositionEnabled = theoryCompositionEnabled;
        this.findings                 = findings;
        if (enabled) {
            log.info("KnowledgeBaseService: ENABLED — formal findings will be extracted "
                    + "from narration and persisted; contradictions detected on demand.");
        }
        if (theoryCompositionEnabled) {
            log.info("KnowledgeBaseService: theory-composition ENABLED — A~f(B) ^ B~g(C) "
                    + "compositions emit ProgramOpenQuestions (cap {} per call, min "
                    + "confidence {}).", COMPOSITION_EMISSION_CAP, COMPOSITION_MIN_CONFIDENCE);
        }
    }

    public boolean isEnabled() { return enabled; }

    /** Phase 4b — wired from UniversityService after both services are constructed. */
    public void setResearchProgramService(ResearchProgramService svc) {
        this.researchProgramService = svc;
    }

    /**
     * Persist findings extracted from the narration LLM. No-op when:
     *  - the findings flag is off,
     *  - the paper or its extracted findings are null/empty.
     *
     * Returns the count of findings actually persisted (zero in the no-op cases
     * or when every entry was invalid).
     */
    public int persistFindings(ResearchPaper paper,
                                List<NarrationResult.ExtractedFinding> extracted,
                                UUID extractedByBotId) {
        if (!enabled || paper == null || extracted == null || extracted.isEmpty()) return 0;
        int count = 0;
        Instant now = Instant.now();
        for (NarrationResult.ExtractedFinding ef : extracted) {
            if (ef == null) continue;
            FormalFinding ff = new FormalFinding(
                    paper.paperId(), extractedByBotId,
                    ef.relationType(),
                    ef.dependentVariable(), ef.independentVariables(),
                    ef.equationForm(),
                    ef.claimStatus(), ef.confidence(),
                    ef.validRegionMin(), ef.validRegionMax(),
                    paper.domain(), now);
            try {
                findings.save(ff);
                count++;
            } catch (Exception e) {
                log.warn("findings persist failed paperId={} depVar={}: {}",
                        paper.paperId(), ef.dependentVariable(), e.getMessage());
            }
        }
        if (count > 0) {
            log.info("findings: persisted {} for paperId={} domain={}",
                    count, paper.paperId(), paper.domain());
        }
        return count;
    }

    // ---- Read-side queries ----

    public List<FormalFinding> findingsForVariable(String dependentVariable) {
        if (dependentVariable == null || dependentVariable.isBlank()) return List.of();
        return findings.findByDependentVariable(dependentVariable);
    }

    public List<FormalFinding> findingsInDomain(String domain) {
        if (domain == null || domain.isBlank()) return List.of();
        return findings.findByDomain(domain);
    }

    public List<FormalFinding> findingsForPaper(String paperId) {
        if (paperId == null) return List.of();
        return findings.findByPaperId(paperId);
    }

    /**
     * Walk all findings in a domain, pair them on shared variables, and emit a
     * pair whenever the relation types are directionally inconsistent
     * (PROPORTIONAL vs INVERSE on the same dependentVariable + same independent
     * variable set). Pairs are de-duplicated and sorted so the higher-confidence
     * finding is the {@code a} side.
     *
     * Phase 4b will extend this to detect contradictions across composed
     * findings (A→B and B→C implying A→C, then a separate finding claiming the
     * opposite direction). For now we only catch direct disagreements.
     */
    public List<ContradictionPair> detectContradictionsInDomain(String domain) {
        List<FormalFinding> domainFindings = findingsInDomain(domain);
        if (domainFindings.size() < 2) return List.of();

        List<ContradictionPair> out = new ArrayList<>();
        Set<String> seenPairKeys = new HashSet<>();
        for (int i = 0; i < domainFindings.size(); i++) {
            FormalFinding a = domainFindings.get(i);
            for (int j = i + 1; j < domainFindings.size(); j++) {
                FormalFinding b = domainFindings.get(j);
                if (!sameVariables(a, b)) continue;
                if (!disagrees(a.getRelationType(), b.getRelationType())) continue;

                FormalFinding hi = a.getConfidence() >= b.getConfidence() ? a : b;
                FormalFinding lo = hi == a ? b : a;
                String key = hi.getId() + ":" + lo.getId();
                if (seenPairKeys.add(key)) {
                    out.add(new ContradictionPair(hi, lo));
                }
            }
        }
        // Sort so the highest-confidence pair surfaces first.
        out.sort(Comparator.comparingDouble(
                (ContradictionPair p) -> p.a().getConfidence()).reversed());
        return out;
    }

    /**
     * Phase 4b — theory composition. For each pair of findings in this domain
     * where {@code f1.independentVariables = [B]} and {@code f2.dependentVariable = B},
     * derive the composed hypothesis {@code A~h(C)} and emit it as a
     * {@link ProgramOpenQuestion} on f1's extracting bot's program in this
     * domain. Cap emissions per call at {@link #COMPOSITION_EMISSION_CAP}.
     *
     * <p>Composition table for {@code RelationType}:
     * <ul>
     *   <li>PROPORTIONAL × PROPORTIONAL → PROPORTIONAL
     *   <li>PROPORTIONAL × INVERSE      → INVERSE
     *   <li>INVERSE × PROPORTIONAL      → INVERSE
     *   <li>INVERSE × INVERSE           → PROPORTIONAL
     *   <li>everything involving THRESHOLD or NULL → skip
     * </ul>
     *
     * <p>Confidence propagates multiplicatively. Valid region is the
     * intersection; if empty, the pair is skipped. Pairs whose composition
     * has already been emitted (tracked by paperId-pair key) are skipped.
     *
     * <p>No-op when either flag is off, or when no {@link ResearchProgramService}
     * has been wired. Returns the number of derived questions emitted.
     */
    public int maybeComposeTheories(String domain) {
        if (!enabled || !theoryCompositionEnabled || domain == null
                || researchProgramService == null) return 0;
        List<FormalFinding> all = findings.findByDomain(domain);
        if (all.size() < 2) return 0;

        // Only single-independent-variable findings participate in MVP composition —
        // multi-IV findings are harder to compose unambiguously.
        List<FormalFinding> simple = new ArrayList<>();
        for (FormalFinding f : all) {
            if (f.getIndependentVariables() != null
                    && f.getIndependentVariables().size() == 1
                    && f.getConfidence() >= COMPOSITION_MIN_CONFIDENCE
                    && (f.getRelationType() == RelationType.PROPORTIONAL
                            || f.getRelationType() == RelationType.INVERSE)) {
                simple.add(f);
            }
        }
        if (simple.size() < 2) return 0;

        record Candidate(FormalFinding f1, FormalFinding f2,
                         RelationType composed, double confidence,
                         double rMin, double rMax, String pairKey) {}
        List<Candidate> candidates = new ArrayList<>();
        for (FormalFinding f1 : simple) {       // A ~ f1(B)
            String b = f1.getIndependentVariables().get(0);
            for (FormalFinding f2 : simple) {   // B ~ f2(C)
                if (f1 == f2) continue;
                if (!b.equalsIgnoreCase(f2.getDependentVariable())) continue;
                String c = f2.getIndependentVariables().get(0);
                // Skip if A == C (would be circular: A ~ f(C) ^ C ~ g(A))
                if (c.equalsIgnoreCase(f1.getDependentVariable())) continue;
                RelationType composed = composeRelationTypes(
                        f1.getRelationType(), f2.getRelationType());
                if (composed == null) continue;
                double rMin = Math.max(f1.getValidRegionMin(), f2.getValidRegionMin());
                double rMax = Math.min(f1.getValidRegionMax(), f2.getValidRegionMax());
                if (rMax <= rMin) continue;
                double confidence = f1.getConfidence() * f2.getConfidence();
                String pairKey = compositionKey(f1.getPaperId(), f2.getPaperId());
                if (composedPairs.contains(pairKey)) continue;
                candidates.add(new Candidate(f1, f2, composed, confidence, rMin, rMax, pairKey));
            }
        }
        // Emit the highest-confidence pairs first, up to the cap.
        candidates.sort(Comparator.comparingDouble(Candidate::confidence).reversed());
        int emitted = 0;
        for (Candidate cand : candidates) {
            if (emitted >= COMPOSITION_EMISSION_CAP) break;
            String questionText = String.format(
                    "Investigate composed hypothesis: %s ~ %s(%s) (derived from %s, %s; "
                    + "confidence=%.2f, validRegion=[%.3f,%.3f])",
                    cand.f1.getDependentVariable(),
                    cand.composed.name(),
                    cand.f2.getIndependentVariables().get(0),
                    cand.f1.getPaperId(), cand.f2.getPaperId(),
                    cand.confidence, cand.rMin, cand.rMax);
            researchProgramService.recordOpenQuestion(
                    cand.f1.getExtractedByBotId(), domain, questionText);
            composedPairs.add(cand.pairKey);
            emitted++;
            log.info("theory composition emitted derived={}~{}({}) from {} + {} confidence={}",
                    cand.f1.getDependentVariable(), cand.composed,
                    cand.f2.getIndependentVariables().get(0),
                    cand.f1.getPaperId(), cand.f2.getPaperId(),
                    String.format("%.2f", cand.confidence));
        }
        return emitted;
    }

    /**
     * Composition table for {@link RelationType} pairs. Returns null when
     * the pair shouldn't compose — THRESHOLD, LOGARITHMIC, CATEGORICAL,
     * and NULL all skip composition. Only PROPORTIONAL × PROPORTIONAL /
     * INVERSE pairs produce a derived type today; the rest are persisted
     * and surfaced but don't fuel theory composition (see
     * {@link RelationType} javadoc for the rationale).
     */
    static RelationType composeRelationTypes(RelationType ab, RelationType bc) {
        if (ab == null || bc == null) return null;
        if (ab == RelationType.THRESHOLD   || bc == RelationType.THRESHOLD)   return null;
        if (ab == RelationType.NULL        || bc == RelationType.NULL)        return null;
        if (ab == RelationType.LOGARITHMIC || bc == RelationType.LOGARITHMIC) return null;
        if (ab == RelationType.CATEGORICAL || bc == RelationType.CATEGORICAL) return null;
        if (ab == RelationType.PROPORTIONAL && bc == RelationType.PROPORTIONAL) return RelationType.PROPORTIONAL;
        if (ab == RelationType.PROPORTIONAL && bc == RelationType.INVERSE) return RelationType.INVERSE;
        if (ab == RelationType.INVERSE && bc == RelationType.PROPORTIONAL) return RelationType.INVERSE;
        if (ab == RelationType.INVERSE && bc == RelationType.INVERSE) return RelationType.PROPORTIONAL;
        return null;
    }

    /** Order-independent key for a pair of paperIds so (A,B) and (B,A) are deduped. */
    private static String compositionKey(String paperA, String paperB) {
        return paperA.compareTo(paperB) <= 0
                ? paperA + "|" + paperB
                : paperB + "|" + paperA;
    }

    private static boolean sameVariables(FormalFinding a, FormalFinding b) {
        if (!a.getDependentVariable().equalsIgnoreCase(b.getDependentVariable())) return false;
        Set<String> ai = new HashSet<>(a.getIndependentVariables());
        Set<String> bi = new HashSet<>(b.getIndependentVariables());
        return ai.equals(bi);
    }

    private static boolean disagrees(RelationType a, RelationType b) {
        // PROPORTIONAL vs INVERSE is the canonical direct contradiction.
        // PROPORTIONAL/INVERSE vs NULL also counts (one paper says there's a
        // relationship, another says there isn't).
        if (a == b) return false;
        if ((a == RelationType.PROPORTIONAL && b == RelationType.INVERSE) ||
            (a == RelationType.INVERSE && b == RelationType.PROPORTIONAL)) return true;
        // LOGARITHMIC is monotonic-increasing (sub-linear); paired with INVERSE
        // (monotonic-decreasing) it's the same kind of direct contradiction as
        // PROPORTIONAL vs INVERSE — opposite-direction claims about the same
        // dependent variable.
        if ((a == RelationType.LOGARITHMIC && b == RelationType.INVERSE) ||
            (a == RelationType.INVERSE && b == RelationType.LOGARITHMIC)) return true;
        // NULL vs any non-NULL counts as a disagreement (one paper says no
        // relationship, the other claims a structural one — categorical,
        // logarithmic, threshold, monotonic — they all contradict NULL).
        if (a == RelationType.NULL || b == RelationType.NULL) return true;
        // CATEGORICAL is about discrete class structure, not direction; it
        // doesn't disagree with continuous monotonic types — they're describing
        // different things, not the same thing differently. Skip.
        // THRESHOLD vs PROPORTIONAL/INVERSE is also a softer disagreement; skip
        // for now and refine when threshold-location comparison lands.
        return false;
    }
}
