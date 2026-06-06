package com.emergentterrain.research.recognise;

import com.emergentterrain.llm.JuliaMathClient;
import com.emergentterrain.llm.PySidecarHealth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 26 §26.4 — unit tests for {@link ConstantRecognitionService}.
 *
 * <p>Uses hand-written anonymous-subclass stubs over the three engine
 * clients (Mockito can't mock these under JDK 25 + Byte Buddy
 * friction; see {@code feedback_jdk_state} memory). This class
 * exercises orchestration only — the engine clients have their own
 * HTTP round-trip tests.
 */
class ConstantRecognitionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Build a sidecar stub that always reports available. Used as a
     *  dependency for the engine clients we subclass; only the
     *  isAvailable() pass-through matters in these tests. */
    private static JuliaMathClient liveSidecar() {
        return new JuliaMathClient(true, "http://stub", 1_000, 500, MAPPER) {
            @Override public boolean isAvailable() { return true; }
        };
    }

    /** py-sidecar health stub that always reports reachable — the
     *  reachability + base-URL dependency for the PyCall engine clients
     *  (Pslq/Lll) we subclass. OeisRecogniserClient still uses liveSidecar()
     *  above (its /recognise/sequence endpoint lives on the main server). */
    private static PySidecarHealth livePyHealth() {
        return new PySidecarHealth("http://stub", 500) {
            @Override public boolean isReachable() { return true; }
        };
    }

    // ── Sequence path ───────────────────────────────────────────────

    @Test
    void recogniseSequence_oeisHit_returnsPrimaryHitWithOeisEvidence() {
        OeisRecogniserClient oeis = oeisHitStub(
                new OeisRecogniserClient.OeisHit(
                        "A000045", "Fibonacci numbers: F(n) = ...", "identity"));

        ConstantRecognitionService svc =
                new ConstantRecognitionService(oeis, null, null);
        RecognitionResult result = svc.recogniseSequence(
                new int[]{1, 1, 2, 3, 5, 8, 13}, "test-paper");

        assertThat(result).isInstanceOf(RecognitionResult.PrimaryHit.class);
        RecognitionResult.PrimaryHit hit = (RecognitionResult.PrimaryHit) result;
        assertThat(hit.engine()).isEqualTo("oeis");
        assertThat(hit.displayName()).contains("Fibonacci");
        assertThat(hit.citationHandle()).isEqualTo("cite-recognised:oeis:A000045");
        assertThat(hit.evidence().evidenceString()).isEqualTo("A000045:identity");
    }

    @Test
    void recogniseSequence_oeisMiss_returnsNoMatch() {
        OeisRecogniserClient oeis = oeisMissStub();
        ConstantRecognitionService svc =
                new ConstantRecognitionService(oeis, null, null);
        RecognitionResult result = svc.recogniseSequence(
                new int[]{987654321, 987654322, 987654323, 987654324}, "test");
        assertThat(result).isInstanceOf(RecognitionResult.NoMatch.class);
    }

    @Test
    void recogniseSequence_oeisClientNull_returnsNoMatch() {
        ConstantRecognitionService svc =
                new ConstantRecognitionService(null, null, null);
        RecognitionResult result = svc.recogniseSequence(
                new int[]{1, 1, 2, 3, 5}, "test");
        assertThat(result).isInstanceOf(RecognitionResult.NoMatch.class);
    }

    // ── PSLQ relation plausibility guard (2026-06-06) ───────────────────────

    private static PslqRecogniserClient.RelationTerm term(int c, String name) {
        return new PslqRecogniserClient.RelationTerm(c, name);
    }

    @Test
    void plausibility_p217_zeroTargetCoefficient_rejected() {
        // P-b72c-217 live fixture: 8-term relation, target coefficient 0 — a
        // "relation" among the basis constants themselves (precision artifact,
        // rendered ".../0").
        assertThat(ConstantRecognitionService.isPlausibleRelation(List.of(
                term(0, "<target>"), term(-6, "1"), term(-11, "pi"),
                term(7, "pi^2"), term(-4, "e"), term(-6, "ln(2)"),
                term(-2, "ln(3)"), term(-5, "gamma"), term(-7, "zeta(3)"))))
                .isFalse();
    }

    @Test
    void plausibility_denseRelation_rejected() {
        // P-b72c-220 shape: target participates but the relation is dense —
        // guaranteed-findable noise for a 4-6 sig-fig mined constant.
        assertThat(ConstantRecognitionService.isPlausibleRelation(List.of(
                term(1, "<target>"), term(-2, "1"), term(3, "pi"),
                term(-2, "pi^2"), term(5, "e"), term(-6, "ln(2)"),
                term(-6, "ln(3)"))))
                .isFalse();
    }

    @Test
    void plausibility_sparseSmallCoefficients_accepted() {
        // zeta(2) = pi^2/6 — the smoke-test hit: [[-6,"<target>"],[1,"pi^2"]].
        assertThat(ConstantRecognitionService.isPlausibleRelation(List.of(
                term(-6, "<target>"), term(0, "1"), term(0, "pi"),
                term(1, "pi^2"), term(0, "e"))))
                .isTrue();
        // Two-term combination still credible (e.g. a*pi + b*ln2).
        assertThat(ConstantRecognitionService.isPlausibleRelation(List.of(
                term(2, "<target>"), term(-3, "pi"), term(1, "ln(2)"))))
                .isTrue();
    }

    @Test
    void plausibility_oversizedCoefficient_rejected() {
        assertThat(ConstantRecognitionService.isPlausibleRelation(List.of(
                term(1, "<target>"), term(-13, "pi"))))
                .isFalse();
    }

    @Test
    void plausibility_degenerateShapes_rejected() {
        // Target alone (value = 0 claim) and empty/null relations.
        assertThat(ConstantRecognitionService.isPlausibleRelation(List.of(
                term(3, "<target>"))))
                .isFalse();
        assertThat(ConstantRecognitionService.isPlausibleRelation(List.of()))
                .isFalse();
        assertThat(ConstantRecognitionService.isPlausibleRelation(null))
                .isFalse();
    }

    @Test
    void recogniseSequence_oeisUnavailable_returnsNoMatch_skipsLookup() {
        AtomicInteger lookups = new AtomicInteger();
        OeisRecogniserClient oeis = new OeisRecogniserClient(
                false, "http://stub", 5_000, liveSidecar(),
                inMemoryCache(), MAPPER) {
            @Override public boolean isAvailable() { return false; }
            @Override public Optional<OeisHit> lookup(int[] seq) {
                lookups.incrementAndGet();
                return Optional.empty();
            }
        };
        ConstantRecognitionService svc =
                new ConstantRecognitionService(oeis, null, null);
        RecognitionResult result = svc.recogniseSequence(
                new int[]{1, 1, 2, 3, 5}, "test");
        assertThat(result).isInstanceOf(RecognitionResult.NoMatch.class);
        assertThat(lookups.get()).isEqualTo(0);
    }

    @Test
    void recogniseSequence_transportFailure_returnsNoMatch_doesNotPropagate() {
        OeisRecogniserClient oeis = new OeisRecogniserClient(
                true, "http://stub", 5_000, liveSidecar(),
                inMemoryCache(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<OeisHit> lookup(int[] seq) {
                throw new OeisRecogniserException("transport", "OEIS down");
            }
        };
        ConstantRecognitionService svc =
                new ConstantRecognitionService(oeis, null, null);
        // Transport failures degrade silently — narration falls back to
        // the naked sequence, never sees the exception.
        RecognitionResult result = svc.recogniseSequence(
                new int[]{1, 1, 2, 3, 5}, "test");
        assertThat(result).isInstanceOf(RecognitionResult.NoMatch.class);
    }

    // ── Constant path: PSLQ-only ─────────────────────────────────────

    @Test
    void recogniseConstant_pslqHit_returnsPrimaryHitWithPslqEvidence() {
        PslqRecogniserClient pslq = pslqHitStub(
                new PslqRecogniserClient.PslqHit(
                        "zeta(3)",
                        List.of(
                                new PslqRecogniserClient.RelationTerm(1, "<target>"),
                                new PslqRecogniserClient.RelationTerm(-1, "zeta(3)")),
                        "BASIC", 200));

        ConstantRecognitionService svc =
                new ConstantRecognitionService(null, pslq, null);
        RecognitionResult result = svc.recogniseConstant(
                "1.20205690315959", "BASIC", "test-paper");

        assertThat(result).isInstanceOf(RecognitionResult.PrimaryHit.class);
        RecognitionResult.PrimaryHit hit = (RecognitionResult.PrimaryHit) result;
        assertThat(hit.engine()).isEqualTo("pslq");
        assertThat(hit.displayName()).isEqualTo("zeta(3)");
        assertThat(hit.citationHandle()).isEqualTo("cite-recognised:pslq:zeta(3)");
        assertThat(hit.evidence().evidenceString())
                .contains("basis=BASIC")
                .contains("precision=200")
                .contains("1*<target>")
                .contains("-1*zeta(3)");
    }

    @Test
    void recogniseConstant_pslqMiss_lllDisabled_returnsNoMatch() {
        PslqRecogniserClient pslq = pslqMissStub();
        AtomicInteger lllCalls = new AtomicInteger();
        LllRecogniserClient lll = new LllRecogniserClient(
                true, false, 5_000, 4, 200, livePyHealth(), MAPPER) {
            @Override public boolean isAvailable() { return false; }
            @Override public Optional<MinPolyHit> recognise(String v) {
                lllCalls.incrementAndGet();
                return Optional.empty();
            }
        };
        ConstantRecognitionService svc =
                new ConstantRecognitionService(null, pslq, lll);
        RecognitionResult result = svc.recogniseConstant(
                "0.78901234567890", "BASIC", "test");
        assertThat(result).isInstanceOf(RecognitionResult.NoMatch.class);
        assertThat(lllCalls.get()).isEqualTo(0);
    }

    // ── Constant path: PSLQ → LLL fall-through ───────────────────────

    @Test
    void recogniseConstant_pslqMiss_lllHit_fallsThroughCorrectly() {
        PslqRecogniserClient pslq = pslqMissStub();
        LllRecogniserClient lll = lllHitStub(
                new LllRecogniserClient.MinPolyHit(
                        "x^4 - 10*x^2 + 1", 4, List.of(1, 0, -10, 0, 1), 200));

        ConstantRecognitionService svc =
                new ConstantRecognitionService(null, pslq, lll);
        RecognitionResult result = svc.recogniseConstant(
                "3.14626436994197", "BASIC", "test");

        assertThat(result).isInstanceOf(RecognitionResult.PrimaryHit.class);
        RecognitionResult.PrimaryHit hit = (RecognitionResult.PrimaryHit) result;
        assertThat(hit.engine()).isEqualTo("lll");
        assertThat(hit.displayName()).isEqualTo("x^4 - 10*x^2 + 1");
        assertThat(hit.citationHandle()).contains("cite-recognised:lll:degree4:");
        assertThat(hit.evidence().evidenceString())
                .contains("degree=4")
                .contains("precision=200")
                .contains("[1, 0, -10, 0, 1]");
    }

    @Test
    void recogniseConstant_pslqHit_skipsLllFallthrough() {
        PslqRecogniserClient pslq = pslqHitStub(
                new PslqRecogniserClient.PslqHit("pi",
                        List.of(
                                new PslqRecogniserClient.RelationTerm(1, "<target>"),
                                new PslqRecogniserClient.RelationTerm(-1, "pi")),
                        "BASIC", 200));
        AtomicInteger lllCalls = new AtomicInteger();
        LllRecogniserClient lll = new LllRecogniserClient(
                true, true, 5_000, 4, 200, livePyHealth(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<MinPolyHit> recognise(String v) {
                lllCalls.incrementAndGet();
                return Optional.empty();
            }
        };

        ConstantRecognitionService svc =
                new ConstantRecognitionService(null, pslq, lll);
        svc.recogniseConstant("3.14159", "BASIC", "test");

        // LLL is enabled but PSLQ already hit — fall-through must NOT fire.
        assertThat(lllCalls.get()).isEqualTo(0);
    }

    @Test
    void recogniseConstant_pslqTransportFail_skipsLllOnSameSidecar() {
        PslqRecogniserClient pslq = new PslqRecogniserClient(
                true, 5_000, 200, livePyHealth(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<PslqHit> recognise(
                    String value, String basis, Integer precisionDigits) {
                throw new PslqRecogniserException("transport", "sidecar down");
            }
        };
        AtomicInteger lllCalls = new AtomicInteger();
        LllRecogniserClient lll = new LllRecogniserClient(
                true, true, 5_000, 4, 200, livePyHealth(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<MinPolyHit> recognise(String v) {
                lllCalls.incrementAndGet();
                return Optional.empty();
            }
        };

        ConstantRecognitionService svc =
                new ConstantRecognitionService(null, pslq, lll);
        RecognitionResult result = svc.recogniseConstant("3.14159", "BASIC", "test");

        assertThat(result).isInstanceOf(RecognitionResult.NoMatch.class);
        // LLL lives on the same sidecar — if PSLQ couldn't reach it,
        // LLL is presumed unreachable too. Skip the fall-through.
        assertThat(lllCalls.get()).isEqualTo(0);
    }

    // ── Optional-engine combinations ─────────────────────────────────

    @Test
    void recogniseConstant_pslqNull_lllOnly_works() {
        LllRecogniserClient lll = lllHitStub(
                new LllRecogniserClient.MinPolyHit("x^2 - 2", 2, List.of(-2, 0, 1), 200));
        ConstantRecognitionService svc =
                new ConstantRecognitionService(null, null, lll);
        RecognitionResult result = svc.recogniseConstant(
                "1.41421356237309", "BASIC", "test");
        assertThat(result).isInstanceOf(RecognitionResult.PrimaryHit.class);
        assertThat(((RecognitionResult.PrimaryHit) result).engine()).isEqualTo("lll");
    }

    @Test
    void recogniseConstant_allEnginesNull_returnsNoMatch() {
        ConstantRecognitionService svc =
                new ConstantRecognitionService(null, null, null);
        RecognitionResult result = svc.recogniseConstant("3.14159", "BASIC", "test");
        assertThat(result).isInstanceOf(RecognitionResult.NoMatch.class);
    }

    // ── Counters ────────────────────────────────────────────────────

    // ── isAvailable aggregate ────────────────────────────────────────

    @Test
    void isAvailable_allEnginesNull_returnsFalse() {
        ConstantRecognitionService svc =
                new ConstantRecognitionService(null, null, null);
        assertThat(svc.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_anyEngineUp_returnsTrue() {
        // Only OEIS up — orchestrator can still answer sequence calls.
        ConstantRecognitionService svc1 = new ConstantRecognitionService(
                oeisMissStub(), null, null);
        assertThat(svc1.isAvailable()).isTrue();
        // Only PSLQ up — orchestrator can still answer constant calls.
        ConstantRecognitionService svc2 = new ConstantRecognitionService(
                null, pslqMissStub(), null);
        assertThat(svc2.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_allEnginesDown_returnsFalse() {
        OeisRecogniserClient oeisDown = new OeisRecogniserClient(
                false, "http://stub", 5_000, liveSidecar(),
                inMemoryCache(), MAPPER) {
            @Override public boolean isAvailable() { return false; }
        };
        PslqRecogniserClient pslqDown = new PslqRecogniserClient(
                false, 5_000, 200, livePyHealth(), MAPPER) {
            @Override public boolean isAvailable() { return false; }
        };
        ConstantRecognitionService svc =
                new ConstantRecognitionService(oeisDown, pslqDown, null);
        assertThat(svc.isAvailable()).isFalse();
    }

    // ── PrimaryHit.formatAsCitationLine ──────────────────────────────

    @Test
    void formatAsCitationLine_includesDisplayNameAndHandle() {
        RecognitionResult.PrimaryHit hit = new RecognitionResult.PrimaryHit(
                new RecognitionEvidence(
                        "pslq", "zeta(3)",
                        "cite-recognised:pslq:zeta(3)",
                        "basis=BASIC;...",
                        50L));
        String line = hit.formatAsCitationLine();
        assertThat(line).contains("zeta(3)");
        assertThat(line).contains("cite-recognised:pslq:zeta(3)");
        assertThat(line).startsWith("Refinement recognition:");
    }

    @Test
    void formatAsCitationLine_handleMatchesValidatorPattern() {
        // The citation line must contain a handle the validator's
        // HANDLE_PATTERN can extract.
        RecognitionResult.PrimaryHit hit = new RecognitionResult.PrimaryHit(
                new RecognitionEvidence(
                        "oeis", "Fibonacci numbers",
                        "cite-recognised:oeis:A000045",
                        "A000045:identity", 12L));
        String line = hit.formatAsCitationLine();
        // Round-trip: validator should see the handle in the line.
        java.util.regex.Matcher m =
                RecognitionCitationValidator.HANDLE_PATTERN.matcher(line);
        assertThat(m.find()).isTrue();
        assertThat(m.group()).isEqualTo("cite-recognised:oeis:A000045");
    }

    @Test
    void stats_reflectSequenceVsConstantSplit() {
        OeisRecogniserClient oeis = oeisHitStub(
                new OeisRecogniserClient.OeisHit("A000040", "primes", "identity"));
        PslqRecogniserClient pslq = pslqMissStub();
        ConstantRecognitionService svc =
                new ConstantRecognitionService(oeis, pslq, null);
        svc.recogniseSequence(new int[]{2, 3, 5, 7, 11}, "t1");
        svc.recogniseConstant("0.789", "BASIC", "t2");
        svc.recogniseConstant("0.456", "BASIC", "t3");

        ConstantRecognitionService.Stats stats = svc.stats();
        assertThat(stats.sequenceCalls()).isEqualTo(1);
        assertThat(stats.constantCalls()).isEqualTo(2);
        assertThat(stats.primaryHits()).isEqualTo(1);
        assertThat(stats.noMatches()).isEqualTo(2);
    }

    // ── Stub builders ────────────────────────────────────────────────

    private static OeisRecogniserClient oeisHitStub(OeisRecogniserClient.OeisHit hit) {
        return new OeisRecogniserClient(
                true, "http://stub", 5_000, liveSidecar(),
                inMemoryCache(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<OeisHit> lookup(int[] seq) {
                return Optional.of(hit);
            }
        };
    }

    private static OeisRecogniserClient oeisMissStub() {
        return new OeisRecogniserClient(
                true, "http://stub", 5_000, liveSidecar(),
                inMemoryCache(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<OeisHit> lookup(int[] seq) {
                return Optional.empty();
            }
        };
    }

    private static PslqRecogniserClient pslqHitStub(PslqRecogniserClient.PslqHit hit) {
        return new PslqRecogniserClient(
                true, 5_000, 200, livePyHealth(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<PslqHit> recognise(
                    String value, String basis, Integer precisionDigits) {
                return Optional.of(hit);
            }
        };
    }

    private static PslqRecogniserClient pslqMissStub() {
        return new PslqRecogniserClient(
                true, 5_000, 200, livePyHealth(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<PslqHit> recognise(
                    String value, String basis, Integer precisionDigits) {
                return Optional.empty();
            }
        };
    }

    private static LllRecogniserClient lllHitStub(LllRecogniserClient.MinPolyHit hit) {
        return new LllRecogniserClient(
                true, true, 5_000, 4, 200, livePyHealth(), MAPPER) {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<MinPolyHit> recognise(String v) {
                return Optional.of(hit);
            }
        };
    }

    /**
     * Lightweight in-memory cache so the OeisRecogniserClient
     * subclasses can construct cleanly. The orchestrator's lookups
     * route through the overridden {@code lookup()} method so the
     * cache is never touched, but the constructor requires a non-null.
     */
    private static OeisCacheRepository inMemoryCache() {
        java.util.Map<String, OeisCacheEntry> data = new java.util.HashMap<>();
        return new OeisCacheRepository() {
            @Override public Optional<OeisCacheEntry> findById(String s) {
                return Optional.ofNullable(data.get(s));
            }
            @Override public <S extends OeisCacheEntry> S save(S entry) {
                data.put(entry.sequenceHash(), entry);
                return entry;
            }
            @Override public boolean existsById(String s) { return data.containsKey(s); }
            @Override public long count() { return data.size(); }
            @Override public void deleteById(String s) { data.remove(s); }
            @Override public void delete(OeisCacheEntry entry) { data.remove(entry.sequenceHash()); }
            @Override public void deleteAll() { data.clear(); }
            @Override public java.util.List<OeisCacheEntry> findAll() {
                return new java.util.ArrayList<>(data.values());
            }
            // Methods we don't use — minimal stubs.
            @Override public java.util.List<OeisCacheEntry> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
            @Override public org.springframework.data.domain.Page<OeisCacheEntry> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
            @Override public java.util.List<OeisCacheEntry> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
            @Override public <S extends OeisCacheEntry> java.util.List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllById(Iterable<? extends String> ids) { throw new UnsupportedOperationException(); }
            @Override public void deleteAll(Iterable<? extends OeisCacheEntry> entities) { throw new UnsupportedOperationException(); }
            @Override public void flush() {}
            @Override public <S extends OeisCacheEntry> S saveAndFlush(S entity) { return save(entity); }
            @Override public <S extends OeisCacheEntry> java.util.List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllInBatch(Iterable<OeisCacheEntry> entities) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllInBatch() { data.clear(); }
            @Override public OeisCacheEntry getOne(String s) { throw new UnsupportedOperationException(); }
            @Override public OeisCacheEntry getById(String s) { throw new UnsupportedOperationException(); }
            @Override public OeisCacheEntry getReferenceById(String s) { throw new UnsupportedOperationException(); }
            @Override public <S extends OeisCacheEntry, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
            @Override public <S extends OeisCacheEntry> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
            @Override public <S extends OeisCacheEntry> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
            @Override public <S extends OeisCacheEntry> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
            @Override public <S extends OeisCacheEntry> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
            @Override public <S extends OeisCacheEntry> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
            @Override public <S extends OeisCacheEntry> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        };
    }
}
