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
        KnowledgeBaseService svc = new KnowledgeBaseService(false, false, repo);
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

    // ---- Phase 4b: theory composition ----

    @Test
    void composeRelationTypesTableMatchesSpec() {
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.PROPORTIONAL, RelationType.PROPORTIONAL))
                .isEqualTo(RelationType.PROPORTIONAL);
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.PROPORTIONAL, RelationType.INVERSE))
                .isEqualTo(RelationType.INVERSE);
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.INVERSE, RelationType.PROPORTIONAL))
                .isEqualTo(RelationType.INVERSE);
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.INVERSE, RelationType.INVERSE))
                .isEqualTo(RelationType.PROPORTIONAL);
        // THRESHOLD and NULL never compose.
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.PROPORTIONAL, RelationType.THRESHOLD)).isNull();
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.PROPORTIONAL, RelationType.NULL)).isNull();
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.NULL, RelationType.PROPORTIONAL)).isNull();
    }

    @Test
    void composeRelationTypesSkipsLogarithmicAndCategoricalOnBothSides() {
        // Composition through a logarithmic step loses information; categorical
        // can't compose with continuous monotonic types at all. Both skip,
        // matching THRESHOLD's behaviour.
        for (RelationType continuous : new RelationType[]{
                RelationType.PROPORTIONAL, RelationType.INVERSE, RelationType.THRESHOLD}) {
            assertThat(KnowledgeBaseService.composeRelationTypes(continuous, RelationType.LOGARITHMIC))
                    .as(continuous + " × LOGARITHMIC").isNull();
            assertThat(KnowledgeBaseService.composeRelationTypes(RelationType.LOGARITHMIC, continuous))
                    .as("LOGARITHMIC × " + continuous).isNull();
            assertThat(KnowledgeBaseService.composeRelationTypes(continuous, RelationType.CATEGORICAL))
                    .as(continuous + " × CATEGORICAL").isNull();
            assertThat(KnowledgeBaseService.composeRelationTypes(RelationType.CATEGORICAL, continuous))
                    .as("CATEGORICAL × " + continuous).isNull();
        }
        // Self-pairs also skip.
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.LOGARITHMIC, RelationType.LOGARITHMIC)).isNull();
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.CATEGORICAL, RelationType.CATEGORICAL)).isNull();
        assertThat(KnowledgeBaseService.composeRelationTypes(
                RelationType.LOGARITHMIC, RelationType.CATEGORICAL)).isNull();
    }

    @Test
    void logarithmicAgainstInverseIsACanonicalContradiction() {
        // LOGARITHMIC is monotonic-increasing (sub-linear); paired with INVERSE
        // (monotonic-decreasing) it's the same direction-conflict as
        // PROPORTIONAL vs INVERSE.
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.LOGARITHMIC, "trust", List.of("density"), 0.8));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.INVERSE,    "trust", List.of("density"), 0.6));

        List<KnowledgeBaseService.ContradictionPair> pairs =
                svc.detectContradictionsInDomain("MATHEMATICS");

        assertThat(pairs).hasSize(1);
    }

    @Test
    void logarithmicAgainstProportionalIsNotAContradiction() {
        // Both are monotonic-increasing; the system doesn't flag them as
        // disagreeing even though their curves differ. Future refinement
        // could compare validity regions, but Iter 9's contract is "same
        // direction → agreement".
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.LOGARITHMIC, "trust", List.of("density")));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.PROPORTIONAL, "trust", List.of("density")));

        assertThat(svc.detectContradictionsInDomain("MATHEMATICS")).isEmpty();
    }

    @Test
    void categoricalDoesNotContradictContinuousMonotonicTypes() {
        // Categorical describes discrete class structure; comparing it to a
        // continuous monotonic claim describes different things, not
        // disagreement. Persisted and queryable, but not flagged.
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.CATEGORICAL, "trust", List.of("density")));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.PROPORTIONAL, "trust", List.of("density")));
        store.put(UUID.randomUUID(), finding("P-3", "MATHEMATICS",
                RelationType.INVERSE, "trust", List.of("density")));
        store.put(UUID.randomUUID(), finding("P-4", "MATHEMATICS",
                RelationType.LOGARITHMIC, "trust", List.of("density")));

        List<KnowledgeBaseService.ContradictionPair> pairs =
                svc.detectContradictionsInDomain("MATHEMATICS");

        // The PROPORTIONAL × INVERSE and PROPORTIONAL × LOGARITHMIC pairs are
        // not flagged (the latter agrees in direction). PROPORTIONAL × INVERSE
        // IS flagged (canonical contradiction). LOGARITHMIC × INVERSE is
        // flagged too (new rule). CATEGORICAL pairs with continuous types are
        // ALL skipped.
        assertThat(pairs).allSatisfy(pair -> {
            assertThat(pair.a().getRelationType()).isNotEqualTo(RelationType.CATEGORICAL);
            assertThat(pair.b().getRelationType()).isNotEqualTo(RelationType.CATEGORICAL);
        });
    }

    @Test
    void nullStillContradictsLogarithmicAndCategorical() {
        // NULL means "we looked and found no relationship." A LOGARITHMIC or
        // CATEGORICAL claim disputes that just as surely as PROPORTIONAL would.
        KnowledgeBaseService svc = enabled();
        store.put(UUID.randomUUID(), finding("P-1", "MATHEMATICS",
                RelationType.LOGARITHMIC, "x", List.of("y")));
        store.put(UUID.randomUUID(), finding("P-2", "MATHEMATICS",
                RelationType.NULL,        "x", List.of("y")));

        assertThat(svc.detectContradictionsInDomain("MATHEMATICS")).hasSize(1);

        KnowledgeBaseService svc2 = enabled();
        store.clear();
        store.put(UUID.randomUUID(), finding("P-3", "PHYSICS",
                RelationType.CATEGORICAL, "x", List.of("y")));
        store.put(UUID.randomUUID(), finding("P-4", "PHYSICS",
                RelationType.NULL,        "x", List.of("y")));

        assertThat(svc2.detectContradictionsInDomain("PHYSICS")).hasSize(1);
    }

    @Test
    void compositionNoOpWhenFlagOff() {
        KnowledgeBaseService svc = enabled();  // findings on, composition off
        ResearchProgramService programs = mock(ResearchProgramService.class);
        svc.setResearchProgramService(programs);

        store.put(UUID.randomUUID(), finding("P-1", "PHYSICS",
                RelationType.PROPORTIONAL, "A", List.of("B")));
        store.put(UUID.randomUUID(), finding("P-2", "PHYSICS",
                RelationType.PROPORTIONAL, "B", List.of("C")));

        assertThat(svc.maybeComposeTheories("PHYSICS")).isZero();
        verify(programs, never()).recordOpenQuestion(any(), any(), any());
    }

    @Test
    void compositionNoOpWhenProgramServiceNotWired() {
        KnowledgeBaseService svc = enabledWithComposition();
        store.put(UUID.randomUUID(), finding("P-1", "PHYSICS",
                RelationType.PROPORTIONAL, "A", List.of("B")));
        store.put(UUID.randomUUID(), finding("P-2", "PHYSICS",
                RelationType.PROPORTIONAL, "B", List.of("C")));

        // Composition flag on but no program service wired → no-op.
        assertThat(svc.maybeComposeTheories("PHYSICS")).isZero();
    }

    @Test
    void compositionEmitsDerivedQuestionForChainedFindings() {
        KnowledgeBaseService svc = enabledWithComposition();
        ResearchProgramService programs = mock(ResearchProgramService.class);
        svc.setResearchProgramService(programs);

        store.put(UUID.randomUUID(), finding("P-1", "PHYSICS",
                RelationType.PROPORTIONAL, "A", List.of("B"), 0.8));
        store.put(UUID.randomUUID(), finding("P-2", "PHYSICS",
                RelationType.PROPORTIONAL, "B", List.of("C"), 0.6));

        int emitted = svc.maybeComposeTheories("PHYSICS");

        assertThat(emitted).isEqualTo(1);
        verify(programs).recordOpenQuestion(any(),
                eq("PHYSICS"),
                org.mockito.ArgumentMatchers.contains("A ~ PROPORTIONAL(C)"));
    }

    @Test
    void compositionSkipsBelowConfidenceFloor() {
        KnowledgeBaseService svc = enabledWithComposition();
        ResearchProgramService programs = mock(ResearchProgramService.class);
        svc.setResearchProgramService(programs);

        // 0.4 confidence is below COMPOSITION_MIN_CONFIDENCE = 0.5.
        store.put(UUID.randomUUID(), finding("P-1", "PHYSICS",
                RelationType.PROPORTIONAL, "A", List.of("B"), 0.4));
        store.put(UUID.randomUUID(), finding("P-2", "PHYSICS",
                RelationType.PROPORTIONAL, "B", List.of("C"), 0.9));

        assertThat(svc.maybeComposeTheories("PHYSICS")).isZero();
        verify(programs, never()).recordOpenQuestion(any(), any(), any());
    }

    @Test
    void compositionSkipsThresholdAndNullRelationTypes() {
        KnowledgeBaseService svc = enabledWithComposition();
        ResearchProgramService programs = mock(ResearchProgramService.class);
        svc.setResearchProgramService(programs);

        store.put(UUID.randomUUID(), finding("P-1", "PHYSICS",
                RelationType.THRESHOLD, "A", List.of("B"), 0.9));
        store.put(UUID.randomUUID(), finding("P-2", "PHYSICS",
                RelationType.PROPORTIONAL, "B", List.of("C"), 0.9));

        assertThat(svc.maybeComposeTheories("PHYSICS")).isZero();
    }

    @Test
    void compositionIsIdempotentForSamePair() {
        KnowledgeBaseService svc = enabledWithComposition();
        ResearchProgramService programs = mock(ResearchProgramService.class);
        svc.setResearchProgramService(programs);

        store.put(UUID.randomUUID(), finding("P-1", "PHYSICS",
                RelationType.PROPORTIONAL, "A", List.of("B"), 0.8));
        store.put(UUID.randomUUID(), finding("P-2", "PHYSICS",
                RelationType.PROPORTIONAL, "B", List.of("C"), 0.8));

        assertThat(svc.maybeComposeTheories("PHYSICS")).isEqualTo(1);
        // Second call: pair already composed → no emission.
        assertThat(svc.maybeComposeTheories("PHYSICS")).isZero();
        verify(programs).recordOpenQuestion(any(), eq("PHYSICS"), any());
    }

    @Test
    void compositionSkipsCircularAEqualsC() {
        KnowledgeBaseService svc = enabledWithComposition();
        ResearchProgramService programs = mock(ResearchProgramService.class);
        svc.setResearchProgramService(programs);

        // A→B and B→A: composition would yield A→A which is circular and useless.
        store.put(UUID.randomUUID(), finding("P-1", "PHYSICS",
                RelationType.PROPORTIONAL, "A", List.of("B"), 0.9));
        store.put(UUID.randomUUID(), finding("P-2", "PHYSICS",
                RelationType.PROPORTIONAL, "B", List.of("A"), 0.9));

        // Both directions of composition would yield A→A or B→B; both are skipped.
        assertThat(svc.maybeComposeTheories("PHYSICS")).isZero();
    }

    @Test
    void compositionRespectsEmissionCap() {
        KnowledgeBaseService svc = enabledWithComposition();
        ResearchProgramService programs = mock(ResearchProgramService.class);
        svc.setResearchProgramService(programs);

        // 7 chained findings producing many composable pairs. Cap is 5.
        for (int i = 0; i < 7; i++) {
            String dep   = "V" + i;
            String indep = "V" + (i + 1);
            store.put(UUID.randomUUID(), finding("P-" + i, "PHYSICS",
                    RelationType.PROPORTIONAL, dep, List.of(indep), 0.8));
        }

        assertThat(svc.maybeComposeTheories("PHYSICS")).isEqualTo(5);
    }

    @Test
    void compositionInverseTimesInverseGivesProportional() {
        KnowledgeBaseService svc = enabledWithComposition();
        ResearchProgramService programs = mock(ResearchProgramService.class);
        svc.setResearchProgramService(programs);

        store.put(UUID.randomUUID(), finding("P-1", "PHYSICS",
                RelationType.INVERSE, "A", List.of("B"), 0.9));
        store.put(UUID.randomUUID(), finding("P-2", "PHYSICS",
                RelationType.INVERSE, "B", List.of("C"), 0.9));

        assertThat(svc.maybeComposeTheories("PHYSICS")).isEqualTo(1);
        verify(programs).recordOpenQuestion(any(),
                eq("PHYSICS"),
                org.mockito.ArgumentMatchers.contains("A ~ PROPORTIONAL(C)"));
    }

    @Test
    void compositionSkipsWhenValidRegionsDisjoint() {
        KnowledgeBaseService svc = enabledWithComposition();
        ResearchProgramService programs = mock(ResearchProgramService.class);
        svc.setResearchProgramService(programs);

        FormalFinding f1 = new FormalFinding(
                "P-1", UUID.randomUUID(), RelationType.PROPORTIONAL, "A", List.of("B"),
                null, MathPhysicsEngine.ClaimStatus.SUPPORTED, 0.9,
                /* min = */ 0.0, /* max = */ 1.0,
                "PHYSICS", Instant.now());
        FormalFinding f2 = new FormalFinding(
                "P-2", UUID.randomUUID(), RelationType.PROPORTIONAL, "B", List.of("C"),
                null, MathPhysicsEngine.ClaimStatus.SUPPORTED, 0.9,
                /* min = */ 5.0, /* max = */ 10.0,   // disjoint from f1's region
                "PHYSICS", Instant.now());
        store.put(UUID.randomUUID(), f1);
        store.put(UUID.randomUUID(), f2);

        assertThat(svc.maybeComposeTheories("PHYSICS")).isZero();
        verify(programs, never()).recordOpenQuestion(any(), any(), any());
    }

    // ---- helpers ----

    private KnowledgeBaseService enabled() {
        return new KnowledgeBaseService(true, false, repo);
    }

    private KnowledgeBaseService enabledWithComposition() {
        return new KnowledgeBaseService(true, true, repo);
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
