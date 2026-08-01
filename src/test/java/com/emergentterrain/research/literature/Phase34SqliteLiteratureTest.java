package com.emergentterrain.research.literature;

import com.emergentterrain.research.PaperDraft;
import com.emergentterrain.service.MathToolSuite;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class Phase34SqliteLiteratureTest {

    @TempDir Path indexDir;

    private Phase34SqliteLiterature indexedAdapter() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE papers (paper_id TEXT PRIMARY KEY, title TEXT, authors TEXT, primary_category TEXT)");
            sql.execute("CREATE TABLE theorems (theorem_id INTEGER PRIMARY KEY, paper_id TEXT)");
            sql.execute("CREATE TABLE fulltext (paper_id TEXT PRIMARY KEY, source_key TEXT)");
            sql.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            sql.execute("CREATE VIRTUAL TABLE theorem_fts USING fts5(theorem_id UNINDEXED, paper_id UNINDEXED, name, slogan, body)");
            sql.execute("CREATE VIRTUAL TABLE fulltext_fts USING fts5(paper_id UNINDEXED, title, text)");
            sql.execute("INSERT INTO papers VALUES ('2401.01234', 'Prime gap bounds', 'A. Author', 'math.NT')");
            sql.execute("INSERT INTO theorems VALUES (1, '2401.01234')");
            sql.execute("INSERT INTO theorem_fts VALUES (1, '2401.01234', 'Prime gap theorem', 'gap bound', 'The prime gap has a tested bound.')");
            sql.execute("INSERT INTO fulltext VALUES ('2401.01234', 'no-problem/2401/2401.01234.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('2401.01234', 'Prime gap bounds', 'The confirmed range gives a prime gap bound with a reproducible calculation.')");
            sql.execute("INSERT INTO metadata VALUES ('fulltext_fts_complete', 'true')");
        }
        return new Phase34SqliteLiterature(indexDir, true, 15_000);
    }

    private Phase34SqliteLiterature indexedAdapterWithPaperReviewScreenedIndex() throws Exception {
        indexedAdapter();
        Path screenedDir = indexDir.resolve("paper-review-screened");
        Files.createDirectories(screenedDir);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + screenedDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            sql.execute("CREATE TABLE papers (paper_id TEXT PRIMARY KEY, title TEXT, authors TEXT, primary_category TEXT)");
            sql.execute("CREATE TABLE fulltext (paper_id TEXT PRIMARY KEY, source_key TEXT)");
            sql.execute("CREATE VIRTUAL TABLE fulltext_fts USING fts5(paper_id UNINDEXED, title, text)");
            sql.execute("INSERT INTO metadata VALUES ('fulltext_fts_complete', 'true')");
            sql.execute("INSERT INTO papers VALUES ('1709.04799', 'Prime gaps and bounds from a screened review source', 'C. Reviewer', 'math.NT')");
            sql.execute("INSERT INTO fulltext VALUES ('1709.04799', 'paper-review-screened:test:1709.04799')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('1709.04799', 'Prime gaps and bounds from a screened review source', "
                    + "'The screened source provides a confirmed prime gap bound and its finite range.')");
        }
        return new Phase34SqliteLiterature(indexDir, true, 15_000, 64,
                new DeterministicLiteratureQueryPlanner(), new PaperReviewScreenedLiterature(screenedDir));
    }

    @Test
    void theoremSearchReturnsHarnessKeyAndReadReturnsIndexedPassage() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        String search = adapter.search("prime gap bound");
        Matcher key = Pattern.compile("\\[(HF[0-9A-F]+)]").matcher(search);
        assertThat(key.find()).isTrue();
        assertThat(search).contains("Prime gap bounds").contains("2401.01234");

        String read = adapter.read(key.group(1), "confirmed range");
        assertThat(read).startsWith("read_reference " + key.group(1))
                .contains("Top passages matching")
                .contains("confirmed range");
        assertThat(adapter.assessRelevance(key.group(1),
                "The confirmed range gives a prime gap bound", "RELEVANT"))
                .contains("ACCEPTED RELEVANT");
        assertThat(adapter.assessRelevance(key.group(1), "made-up wording not returned by the source", "RELEVANT"))
                .contains("supporting_excerpt must be a focused passage");
    }

    @Test
    void routedShardsIssueKeysAndRouteTheSubsequentReadToTheirOwningShard() throws Exception {
        indexedAdapter();
        Path shardsDir = indexDir.resolve("shards");
        for (String shard : List.of("number-theory", "analysis", "algebra", "combinatorics", "geometry",
                "probability", "cross-list")) {
            Path directory = shardsDir.resolve(shard);
            Files.createDirectories(directory);
            Files.copy(indexDir.resolve("literature.db"), directory.resolve("literature.db"));
        }
        Files.writeString(shardsDir.resolve("catalog.json"), "{\"schema_version\":1}\n");
        LiteratureQueryPlanner planner = new DeterministicLiteratureQueryPlanner();
        ShardedPhase34Literature shards = new ShardedPhase34Literature(shardsDir, true, 15_000,
                64, planner, 2);
        Phase34SqliteLiterature routed = new Phase34SqliteLiterature(indexDir, true, 15_000, 64,
                planner, null, shards);

        String search = routed.search("prime gap bound");
        Matcher key = Pattern.compile("\\[(HF[0-9A-F]+)]").matcher(search);

        assertThat(search).contains("routed sharded primary", "EVIDENCE");
        assertThat(key.find()).isTrue();
        assertThat(routed.read(key.group(1), "confirmed range"))
                .startsWith("read_reference " + key.group(1))
                .contains("Prime gap bounds");
    }

    @Test
    void phase35MergesPaperReviewScreenedAndPrimaryMatchesAndReadsTheIssuedScreenedKey() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapterWithPaperReviewScreenedIndex();

        String search = adapter.search("prime gap bound");
        List<String> keys = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\[(HF[0-9A-F]+)]").matcher(search);
        while (matcher.find()) keys.add(matcher.group(1));

        assertThat(search).contains("Prime gap bounds", "screened review source", "index: primary",
                "index: paper-review-screened");
        assertThat(keys).hasSize(2);
        assertThat(adapter.read(keys.get(1), "confirmed prime gap bound"))
                .contains("paper-review-screened literature index", "confirmed prime gap bound");
    }

    @Test
    void phase35FailsClosedWhenAnExtraClaimAnchorCannotBeCorroborated() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        assertThat(adapter.search("prime gap nonexistent"))
                .contains("no theorem-index matches")
                .doesNotContain("EVIDENCE");
    }

    @Test
    void phase35FullTextFallbackIssuesAKeyWhenTheTheoremCorpusHasNoLinkedRecord() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO papers VALUES ('1508.06394', 'Error terms for a divisor problem', 'A. Author', 'math.NT')");
            sql.execute("INSERT INTO fulltext VALUES ('1508.06394', 'no-problem/1508/1508.06394.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('1508.06394', 'Error terms for a divisor problem', "
                    + "'The error term in the classical Dirichlet divisor problem is studied here.')");
        }

        String search = adapter.search("Dirichlet divisor error");
        Matcher key = Pattern.compile("\\[(HF[0-9A-F]+)]").matcher(search);
        assertThat(key.find()).isTrue();
        assertThat(search).contains("EVIDENCE").contains("1508.06394");
        assertThat(adapter.read(key.group(1), "error term"))
                .startsWith("read_reference " + key.group(1))
                .contains("error term");
    }

    @Test
    void phase35FullTextFallbackAcceptsATitlelessRecordOnlyWithAThreeAnchorExactSubjectMatch() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO fulltext VALUES ('1508.06394', 'no-problem/1508/1508.06394.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('1508.06394', '', "
                    + "'The error term in the classical Dirichlet divisor problem is studied here.')");
        }

        String search = adapter.search("Dirichlet divisor error");

        assertThat(search).contains("EVIDENCE").contains("1508.06394");
    }

    @Test
    void phase35FullTextFallbackRejectsGenericBodyOverlapWithoutATitleSubjectAnchor() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO papers VALUES ('2601.00001', 'Dark matter density', 'A. Author', 'astro-ph')");
            sql.execute("INSERT INTO fulltext VALUES ('2601.00001', 'no-problem/2601/2601.00001.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('2601.00001', 'Dark matter density', "
                    + "'An abundant number density can arise in this unrelated physical model.')");
        }

        assertThat(adapter.search("abundant number density"))
                .contains("no theorem-index matches")
                .doesNotContain("EVIDENCE", "HF");
    }

    @Test
    void phase35FullTextFallbackRejectsATitlelessGenericBodyOverlap() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO fulltext VALUES ('2601.00001', 'no-problem/2601/2601.00001.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('2601.00001', '', "
                    + "'An abundant number density can arise in this unrelated physical model.')");
        }

        assertThat(adapter.search("abundant number density"))
                .contains("no theorem-index matches")
                .doesNotContain("EVIDENCE", "HF");
    }

    @Test
    void phase35FailsClosedForTitlelessProceduralCensusOverlap() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO fulltext VALUES ('2601.00002', 'no-problem/2601/2601.00002.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('2601.00002', '', "
                    + "'An exact census reports a fraction for an unrelated astronomy population.')");
        }

        assertThat(adapter.search("The finite-window fraction is an exact census but does not itself establish a limit"))
                .contains("no sufficiently specific literature anchors")
                .doesNotContain("EVIDENCE", "HF");
    }

    @Test
    void phase35NormalizesInstructionsAndKeepsAtLeastTwoAndAnchors() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "Search the literature for prime gap bounds and prior papers",
                java.util.Map.of("prime", 10, "gap", 20, "bounds", 30));

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("prime AND gap")
                .allMatch(query -> query.contains(" AND ") && !query.contains(" OR "));
        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::requiredTitleAnchors)
                .containsExactly(List.of("prime", "gap"));
    }

    @Test
    void phase35RejectsTheObservedGeometryFalsePositiveForPrimeGapEvidence() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO papers VALUES ('2109.02156', "
                    + "'Linking disjoint axis-parallel segments into a simple polygon is hard too', "
                    + "'R. Jiang', 'cs.CG')");
            sql.execute("INSERT INTO theorems VALUES (2, '2109.02156')");
            sql.execute("INSERT INTO theorem_fts VALUES (2, '2109.02156', 'Segment visibility theorem', "
                    + "'prime gap', 'An unrelated geometry construction happens to contain both prime and gap.')");
            sql.execute("INSERT INTO fulltext VALUES ('2109.02156', 'no-problem/2109/2109.02156.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('2109.02156', "
                    + "'Linking disjoint axis-parallel segments into a simple polygon is hard too', "
                    + "'The unrelated geometry text contains prime and gap as lexical coincidences.')");
        }

        String search = adapter.search("prime gap");

        assertThat(search).contains("EVIDENCE", "Prime gap bounds")
                .doesNotContain("Linking disjoint axis-parallel segments");
    }

    @Test
    void phase35AcceptsNormalizedPluralSubjectAnchorsInTitleMetadata() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO papers VALUES ('2501.00002', 'Prime gaps at large scale', 'A. Author', 'math.NT')");
            sql.execute("INSERT INTO theorems VALUES (2, '2501.00002')");
            sql.execute("INSERT INTO theorem_fts VALUES (2, '2501.00002', 'Prime gap theorem', "
                    + "'prime gap', 'A prime gap result is established.')");
            sql.execute("INSERT INTO fulltext VALUES ('2501.00002', 'no-problem/2501/2501.00002.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('2501.00002', 'Prime gaps at large scale', "
                    + "'A prime gap result is established.')");
        }

        assertThat(adapter.search("prime gap")).contains("Prime gaps at large scale");
    }

    @Test
    void phase35AcceptsAnAiProposedSubjectPairOnlyWhenItIsGroundedAndSafe() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        String search = adapter.search("prime gap bound", new LiteratureQueryProposal(
                List.of("prime", "gap"), "compare a finite bound with prior work",
                "prior prime-gap work may identify the established comparison"));

        assertThat(search).contains("EVIDENCE", "Prime gap bounds");
    }

    @Test
    void phase35KeepsValidatedProposalThenTriesClaimGroundedDefinitionAndMethodPairs() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "Dirichlet divisor error oscillation estimate", java.util.Map.of(),
                new LiteratureQueryProposal(List.of("dirichlet", "divisor"),
                        "locate the established estimate", "definition and theorem context"));

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("dirichlet AND divisor", "\"dirichlet divisor\"",
                        "\"divisor error\"", "\"error oscillation\"", "\"oscillation estimate\"");
    }

    @Test
    void phase35FailsClosedForAnAiProposalWithProceduralOrUngroundedAnchors() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        assertThat(adapter.search("prime gap bound", new LiteratureQueryProposal(
                List.of("exact", "census"), "report a count", "generic reporting words")))
                .contains("no valid two-anchor subject")
                .doesNotContain("EVIDENCE");
        assertThat(adapter.search("prime gap bound", new LiteratureQueryProposal(
                List.of("dark", "matter"), "unrelated relation", "not in the claim")))
                .contains("not grounded in the supplied claim")
                .doesNotContain("EVIDENCE");
    }

    @Test
    void phase35MapsCaseDistinctOmegaNotationToSafePrimeFactorAnchors() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "The omega Omega gap converges to a prime-power correction constant.",
                java.util.Map.of(), new LiteratureQueryProposal(
                        List.of("omega", "Omega"), "compare the two prime-factor functions",
                        "prior work may state their correction term"));

        assertThat(plan.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.expression()).isEqualTo("prime AND factors");
            assertThat(candidate.evidenceAnchors()).containsExactly("prime", "factors", "multiplicity");
        });
    }

    @Test
    void phase35PlannerRejectsProceduralCensusPhrasesWithoutASubject() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "The finite-window fraction is an exact census but does not itself establish a limit",
                java.util.Map.of("fraction", 10, "exact", 20, "census", 30, "establish", 40));

        assertThat(plan.candidates()).isEmpty();
    }

    @Test
    void phase35SkipsDuplicateIdentifierTokensAndKeepsDistinctSubjectPhrases() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "omega_Omega_gap density",
                java.util.Map.of());

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"omega gap\"", "\"gap density\"")
                .doesNotContain("\"omega omega\"");
    }

    @Test
    void phase35SuppressesGenericConnectorPhrasesBeforeTheSubjectPhrase() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "two term empirical inequality for twin prime statistic versus prime sq converges", java.util.Map.of());

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .contains("\"twin prime\"")
                .doesNotContain("\"two term\"", "\"term empirical\"", "\"inequality twin\"",
                        "\"prime statistic\"", "\"prime sq\"");
    }

    @Test
    void phase35SuppressesDiscriminationFramingThatCouldMatchAnUnrelatedFullTextBody() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "prime gaps may not discriminate between alternative asymptotic laws", java.util.Map.of());

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"prime gaps\"")
                .doesNotContain("\"discriminate between\"");
    }

    @Test
    void phase35RejectsGenericPairsCapturedInRunLogsButKeepsTechnicalPhrases() {
        LiteratureQueryPlan dirichlet = new DeterministicLiteratureQueryPlanner().plan(
                "dirichlet divisor error has order one oscillation envelope", java.util.Map.of());
        assertThat(dirichlet.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"dirichlet divisor\"", "\"divisor error\"", "\"oscillation envelope\"")
                .doesNotContain("\"error has\"", "\"has order\"", "\"order one\"", "\"one oscillation\"");

        LiteratureQueryPlan measurement = new DeterministicLiteratureQueryPlanner().plan(
                "maximum prime gap consecutive high location tracks squared", java.util.Map.of());
        assertThat(measurement.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"maximum prime\"", "\"prime gap\"")
                .doesNotContain("\"consecutive high\"", "\"high location\"", "\"tracks squared\"");

        LiteratureQueryPlan wigert = new DeterministicLiteratureQueryPlanner().plan(
                "has wigert max divisor", java.util.Map.of());
        assertThat(wigert.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .contains("\"wigert max\"", "\"max divisor\"")
                .doesNotContain("\"has wigert\"");
    }

    @Test
    void phase35RejectsGenericModelProsePairsFromTheLatestRun() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "integers satisfying omega obeys richly divisible density mean approximately 77316",
                java.util.Map.of());

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"richly divisible\"", "\"divisible density\"")
                .doesNotContain("\"integers satisfying\"", "\"satisfying omega\"", "\"omega obeys\"",
                        "\"obeys richly\"", "\"density mean\"", "\"mean approximately\"");
    }

    @Test
    void phase35RejectsNumericAndRelationProseAnchorsFromThePaperReviewRun() {
        LiteratureQueryPlan cramer = new DeterministicLiteratureQueryPlanner().plan(
                "cramer gap ratio requiring density feature", java.util.Map.of());
        assertThat(cramer.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"cramer gap\"", "\"gap ratio\"")
                .doesNotContain("\"requiring density\"", "\"density feature\"");

        LiteratureQueryPlan twinPrime = new DeterministicLiteratureQueryPlanner().plan(
                "twin prime density 0000 richly divisible density 9700 minus", java.util.Map.of());
        assertThat(twinPrime.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"twin prime\"", "\"prime density\"", "\"richly divisible\"",
                        "\"divisible density\"")
                .doesNotContain("\"density 0000\"", "\"0000 richly\"", "\"density 9700\"");
    }

    @Test
    void phase35PreservesClaimSubjectWhenDocumentCountsMissOneAnchor() {
        LiteratureQueryPlan plan = new DeterministicLiteratureQueryPlanner().plan(
                "twin prime density related to divisor density", java.util.Map.of("twin", 10, "divisor", 20));

        assertThat(plan.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .contains("\"twin prime\"", "\"prime density\"")
                .doesNotContain("\"twin divisor\"");
    }

    @Test
    void phase35RejectsComparativeProseButRetainsTheAbundantNumberSubjectPair() {
        LiteratureQueryPlan generic = new DeterministicLiteratureQueryPlanner().plan(
                "elevated relative local baseline remains below asymptotically like decreases across",
                java.util.Map.of());
        assertThat(generic.candidates()).isEmpty();

        LiteratureQueryPlan abundant = new DeterministicLiteratureQueryPlanner().plan(
                "abundant number density elevated relative to a local baseline", java.util.Map.of());
        assertThat(abundant.candidates()).extracting(LiteratureQueryPlan.Candidate::expression)
                .containsExactly("\"abundant number\"")
                .doesNotContain("\"number density\"", "\"elevated relative\"", "\"local baseline\"");

        LiteratureQueryPlan p77 = new DeterministicLiteratureQueryPlanner().plan(
                "The density of abundant integers in [120,420) is elevated relative to a local baseline "
                        + "and its finite-range deviation should be tracked across scales.", java.util.Map.of());
        assertThat(p77.candidates()).isEmpty();
    }

    @Test
    void phase35BoundsSearchToolOutputBeforeItReachesTheModelProxy() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            String longTitle = "Prime giant " + "x".repeat(20_000);
            sql.execute("INSERT INTO papers VALUES ('2501.00001', '" + longTitle + "', 'A. Author', 'math.NT')");
            sql.execute("INSERT INTO theorems VALUES (2, '2501.00001')");
            sql.execute("INSERT INTO theorem_fts VALUES (2, '2501.00001', 'Prime giant theorem', 'prime giant', 'prime giant')");
            sql.execute("INSERT INTO fulltext VALUES ('2501.00001', 'no-problem/2501/2501.00001.html')");
            sql.execute("INSERT INTO fulltext_fts VALUES ('2501.00001', 'Prime giant', 'prime giant')");
        }

        String output = adapter.search("prime giant");

        assertThat(output).contains("2501.00001").hasSizeLessThanOrEqualTo(12_000);
    }

    @Test
    void phase35StagingAlwaysRetainsTheEstablishedFullCandidateRange() {
        assertThat(Phase34SqliteLiterature.stagedCandidateLimits(64)).containsExactly(64, 512);
        assertThat(Phase34SqliteLiterature.stagedCandidateLimits(512)).containsExactly(512);
    }

    @Test
    void phase35TriesTheoremNameBeforeTheUnchangedGeneralCandidate() {
        LiteratureQueryPlan.Candidate candidate = new LiteratureQueryPlan.Candidate(
                LiteratureQueryPlan.QueryKind.AND_PAIR, List.of("graph", "isomorphism"),
                List.of("graph", "isomorphism"), List.of());
        List<Phase34SqliteLiterature.SearchCandidate> expanded =
                Phase34SqliteLiterature.theoremNameFirstCandidates(List.of(
                        new Phase34SqliteLiterature.SearchCandidate(candidate.expression(), candidate.queryAnchors(),
                                candidate.evidenceAnchors(), candidate.minimumEvidenceMatches(),
                                candidate.requiredTitleAnchors())));

        assertThat(expanded).extracting(Phase34SqliteLiterature.SearchCandidate::expression)
                .containsExactly("name : graph AND name : isomorphism", "graph AND isomorphism");
    }

    @Test
    void phase35FailsClosedWhenOnlyGenericClaimFramingRemains() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        assertThat(adapter.search("finite scale normalized maximal coordinate"))
                .contains("no sufficiently specific literature anchors")
                .doesNotContain("EVIDENCE");
    }

    @Test
    void phase35FlagCanRestoreTheLegacyBroadFallback() throws Exception {
        Phase34SqliteLiterature legacy = new Phase34SqliteLiterature(indexDir, false, 15_000);
        indexedAdapter();

        assertThat(legacy.search("prime nonexistent"))
                .contains("Prime gap bounds")
                .doesNotContain("no theorem-index matches");
    }

    @Test
    void unknownKeyIsRejectedRatherThanResolvedAsAPaperId() throws Exception {
        Phase34SqliteLiterature adapter = indexedAdapter();

        assertThat(adapter.read("HFDEADBEEF", "prime gap"))
                .contains("unknown Phase 34 key");
    }

    @Test
    void unavailableIndexFailsSoft() {
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(indexDir);

        assertThat(adapter.search("prime gap")).contains("not available");
    }

    @Test
    void incompleteFullTextIndexIsNotAvailableOrAdvertised() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + indexDir.resolve("literature.db"));
             Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            sql.execute("INSERT INTO metadata VALUES ('fulltext_fts_complete', 'false')");
        }
        Phase34SqliteLiterature adapter = new Phase34SqliteLiterature(indexDir);
        MathToolSuite suite = new MathToolSuite(null, new ObjectMapper());
        suite.setPhase34SqliteLiterature(adapter);

        assertThat(adapter.available()).isFalse();
        assertThat(suite.toolsJson(false, true)).doesNotContain("search_literature")
                .doesNotContain("read_reference");
    }

    @Test
    void toolSuiteUsesTheAdapterOnlyWhenItIsExplicitlyWired() throws Exception {
        MathToolSuite suite = new MathToolSuite(null, new ObjectMapper());
        suite.setPhase34SqliteLiterature(indexedAdapter());
        PaperDraft draft = new PaperDraft(null, "bot", "MATHEMATICS", "prime gap bound", "title",
                null, null, null, null, List.of(), 0.0, 0.0, 0.0, 0L, null, 0, "P-test",
                null, null, false, null, null, List.of(), null, null, null, null);

        assertThat(suite.toolsJson(false, true))
                .contains("search_literature")
                .contains("read_reference")
                .contains("assess_reference_relevance");
        String search = suite.buildExecutor(draft, new MathToolSuite.ToolMetrics())
                .apply("{\"name\":\"search_literature\",\"arguments\":{\"claim\":\"prime gap bound\","
                        + "\"subject_anchors\":[\"prime\",\"gap\"],\"claimed_relation\":\"bound comparison\","
                        + "\"relevance_rationale\":\"prior prime-gap work positions this bound\"}}");
        Matcher key = Pattern.compile("\\[(HF[0-9A-F]+)]").matcher(search);
        assertThat(key.find()).isTrue();
        String read = suite.buildExecutor(draft, new MathToolSuite.ToolMetrics()).apply(
                "{\"name\":\"read_reference\",\"arguments\":{\"key\":\"" + key.group(1)
                        + "\",\"query\":\"confirmed range\"}}");
        assertThat(read).startsWith("read_reference " + key.group(1));
        assertThat(suite.buildExecutor(draft, new MathToolSuite.ToolMetrics()).apply(
                "{\"name\":\"assess_reference_relevance\",\"arguments\":{\"key\":\"" + key.group(1)
                        + "\",\"supporting_excerpt\":\"The confirmed range gives a prime gap bound\","
                        + "\"verdict\":\"RELEVANT\",\"rationale\":\"same finite bound\"}}"))
                .contains("ACCEPTED RELEVANT");

        assertThat(suite.buildExecutor(draft, new MathToolSuite.ToolMetrics()).apply(
                "{\"name\":\"search_literature\",\"arguments\":{\"claim\":\"prime gap bound\","
                        + "\"subject_anchors\":[\"exact\",\"census\"],\"claimed_relation\":\"count\","
                        + "\"relevance_rationale\":\"generic words\"}}"))
                .contains("no valid two-anchor subject")
                .doesNotContain("EVIDENCE");
    }
}
