package com.emergentterrain.research;

import com.emergentterrain.repo.FormalFindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for Phase 4 Iter 9 KnowledgeBaseService. The repository is mocked
 * with a small in-memory store so contradiction detection can run against
 * realistic fixture data.
 */
class KnowledgeBaseServiceTest {

    private FormalFindingRepository repo;
    private final Map<UUID, FormalFinding> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(FormalFindingRepository.class);
        store.clear();
        when(repo.save(any(FormalFinding.class))).thenAnswer(inv -> {
            FormalFinding f = inv.getArgument(0);
            if (f.getId() == null) {
                try {
                    java.lang.reflect.Field idF = FormalFinding.class.getDeclaredField("id");
                    idF.setAccessible(true);
                    idF.set(f, UUID.randomUUID());
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            store.put(f.getId(), f);
            return f;
        });
        when(repo.findByDomain(any())).thenAnswer(inv -> store.values().stream()
                .filter(f -> ((String) inv.getArgument(0)).equals(f.getDomain()))
                .toList());
        when(repo.findByDependentVariable(any())).thenAnswer(inv -> store.values().stream()
                .filter(f -> ((String) inv.getArgument(0)).equalsIgnoreCase(f.getDependentVariable()))
                .toList());
        when(repo.findByPaperId(any())).thenAnswer(inv -> store.values().stream()
                .filter(f -> ((String) inv.getArgument(0)).equals(f.getPaperId()))
                .toList());
    }

    @Test
    void disabledServicePersistsNothing() {
        KnowledgeBaseService svc = new KnowledgeBaseService(false, repo);
        ResearchPaper p = paper("P-1", "MATHEMATICS");

        int count = svc.persistFindings(p, List.of(extracted(RelationType.PROPORTIONAL,
                "trust", List.of("highway_density"))), UUID.randomUUID());

        assertThat(count).isZero();
        verify(repo, never()).save(any());
    }

    @Test
    void enabledServicePersistsOneRowPerFinding() {
        KnowledgeBaseService svc = enabled();
        ResearchPaper p = paper("P-1", "MATHEMATICS");
        AtomicInteger saves = new AtomicInteger();
        when(repo.save(any(FormalFinding.class))).thenAnswer(inv -> {
            saves.incrementAndGet();
            FormalFinding f = inv.getArgument(0);
            store.put(UUID.randomUUID(), f);
            return f;
        });

        int count = svc.persistFindings(p, List.of(
                extracted(RelationType.PROPORTIONAL, "trust", List.of("highway_density")),
                extracted(RelationType.INVERSE,      "violence", List.of("welfare_budget"))),
                UUID.randomUUID());

        assertThat(count).isEqualTo(2);
        assertThat(saves.get()).isEqualTo(2);
    }

    @Test
    void invalidEntriesAreSkippedSilently() {
        KnowledgeBaseService svc = enabled();
        ResearchPaper p = paper("P-1", "MATHEMATICS");

        // First entry is null, second is valid → only one persists.
        int count = svc.persistFindings(p,
                java.util.Arrays.asList(null,
                        extracted(RelationType.PROPORTIONAL, "trust", List.of("highway_density"))),
                UUID.randomUUID());

        assertThat(count).isEqualTo(1);
    }

    @Test
    void findingsForVariableQueriesByDependentVar() {
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.PROPORTIONAL, "trust", List.of("density")));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.INVERSE, "violence", List.of("welfare")));

        List<FormalFinding> result = svc.findingsForVariable("trust");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDependentVariable()).isEqualTo("trust");
    }

    @Test
    void contradictionDetectionFindsDirectDisagreement() {
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.PROPORTIONAL, "trust", List.of("highway_density"), 0.8));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.INVERSE, "trust", List.of("highway_density"), 0.6));

        List<KnowledgeBaseService.ContradictionPair> pairs =
                svc.detectContradictionsInDomain("MATHEMATICS");

        assertThat(pairs).hasSize(1);
        // Higher-confidence finding sorts first.
        assertThat(pairs.get(0).a().getConfidence()).isEqualTo(0.8);
        assertThat(pairs.get(0).b().getConfidence()).isEqualTo(0.6);
    }

    @Test
    void nullFindingContradictsAnyRelationOnSameVariables() {
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.PROPORTIONAL, "trust", List.of("highway_density"), 0.7));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.NULL, "trust", List.of("highway_density"), 0.5));

        List<KnowledgeBaseService.ContradictionPair> pairs =
                svc.detectContradictionsInDomain("MATHEMATICS");

        assertThat(pairs).hasSize(1);
    }

    @Test
    void thresholdDoesNotContradictProportionalForNow() {
        // Phase 4 doc: THRESHOLD vs PROPORTIONAL/INVERSE is a softer disagreement
        // we don't flag in Iter 9 — Phase 4b refines this.
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.PROPORTIONAL, "trust", List.of("density"), 0.7));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.THRESHOLD, "trust", List.of("density"), 0.6));

        assertThat(svc.detectContradictionsInDomain("MATHEMATICS")).isEmpty();
    }

    @Test
    void differentVariablesAreNotContradictions() {
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.PROPORTIONAL, "trust", List.of("density")));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.INVERSE,      "trust", List.of("welfare")));

        // Same dependent variable but different independents → no contradiction.
        assertThat(svc.detectContradictionsInDomain("MATHEMATICS")).isEmpty();
    }

    @Test
    void contradictionsScopedToDomain() {
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.PROPORTIONAL, "x", List.of("y")));
        store.put(UUID.randomUUID(), finding("P-2", "PHYSICS",
                RelationType.INVERSE,      "x", List.of("y")));

        // The pair is across domains — detectContradictionsInDomain only sees one.
        assertThat(svc.detectContradictionsInDomain("MATHEMATICS")).isEmpty();
        assertThat(svc.detectContradictionsInDomain("PHYSICS")).isEmpty();
    }

    @Test
    void findingsForPaperReturnsOnlyRowsForThatPaper() {
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.PROPORTIONAL, "x", List.of("y")));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.PROPORTIONAL, "x", List.of("y")));
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.INVERSE, "z", List.of("w")));

        assertThat(svc.findingsForPaper("P-1")).hasSize(2);
        assertThat(svc.findingsForPaper("P-2")).hasSize(1);
    }

    // ---- helpers ----

    private KnowledgeBaseService enabled() {
        return new KnowledgeBaseService(true, repo);
    }

    private static ResearchPaper paper(String paperId, String domain) {
        return new ResearchPaper(
                paperId, UUID.randomUUID(), UUID.randomUUID().toString(),
                domain, "topic-x",
                "title", "h", "THEORETICAL", "f", "c",
                List.of(), 0.5, "PUBLISHED",
                100L, Instant.now(), 1, 0.0, null, null);
    }

    private static NarrationResult.ExtractedFinding extracted(
            RelationType relation, String dep, List<String> indeps) {
        return new NarrationResult.ExtractedFinding(
                relation, dep, indeps, /* equation = */ null,
                MathPhysicsEngine.ClaimStatus.SUPPORTED, /* confidence = */ 0.7,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    private static FormalFinding finding(String paperId, String domain, RelationType relation,
                                         String dep, List<String> indeps) {
        return finding(paperId, domain, relation, dep, indeps, 0.7);
    }

    private static FormalFinding finding(String paperId, String domain, RelationType relation,
                                         String dep, List<String> indeps, double confidence) {
        return new FormalFinding(
                paperId, UUID.randomUUID(), relation, dep, indeps,
                /* equationForm = */ null,
                MathPhysicsEngine.ClaimStatus.SUPPORTED, confidence,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                domain, Instant.now());
    }
}
