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
 * Theory composition ({@code A~f(B) ∧ B~g(C) → A~h(C)}) is deferred to
 * Phase 4b per the phase doc — contradiction detection is the first
 * automation, composition follows.
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    /** Two findings on the same variables disagree when their RelationTypes flip direction. */
    public record ContradictionPair(FormalFinding a, FormalFinding b) {}

    private final boolean enabled;
    private final FormalFindingRepository findings;

    public KnowledgeBaseService(
            @Value("${app.research.findings.enabled:false}") boolean enabled,
            FormalFindingRepository findings) {
        this.enabled  = enabled;
        this.findings = findings;
        if (enabled) {
            log.info("KnowledgeBaseService: ENABLED — formal findings will be extracted "
                    + "from narration and persisted; contradictions detected on demand.");
        }
    }

    public boolean isEnabled() { return enabled; }

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
        if (a == RelationType.NULL || b == RelationType.NULL) return true;
        // THRESHOLD vs PROPORTIONAL/INVERSE is a softer disagreement; for now
        // skip — Phase 4b can refine with threshold-location comparison.
        return false;
    }
}
