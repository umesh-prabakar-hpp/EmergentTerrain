package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 32 §32.5 step 2 (phase-32-openalex-corpus.md item B) — offline number-theory corpus
 * builder. Operational tooling (run via {@code scripts/build-nt-corpus.sh}, no runtime flag,
 * never on the request path): pulls work metadata + abstracts from OpenAlex into a local corpus
 * directory that the §32.5 grounding sidecar (step 3) will index. Domain-SPECIFIC by design —
 * this is the NT-corpus asset build, not pipeline code.
 *
 * <p><b>Selection (2026-07-04 reframe):</b> targeted searches seeded from the
 * {@code ScalarOpenness}/{@code MEASUREMENT_FRAMES} object list (the quantities every measurement
 * paper must situate against), PLUS a breadth pull of the most-cited works in the Analytic Number
 * Theory topic (T11166, probed live). Abstracts are reconstructed from
 * {@code abstract_inverted_index}; dedup is by OpenAlex work id across runs (resumable — the
 * response cache also makes repeated pages free).
 *
 * <p><b>Outputs</b> under the corpus dir (default {@code ~/.emergentterrain/corpus-nt}):
 * {@code abstracts.jsonl} (one work per line: id, doi, title, year, venue, authors, abstract,
 * cited_by_count, query), {@code MANIFEST.json} (queries, counts, spend, date — reproducibility),
 * {@code openalex-cache.jsonl} + {@code openalex-ledger.json} (client state).
 *
 * <p><b>Env knobs:</b> {@code OPENALEX_API_KEY} (optional — anonymous tier gives ~$0.10/day;
 * a free key gives $1/day), {@code OPENALEX_MAILTO}, {@code CORPUS_DIR},
 * {@code PAGES_PER_QUERY} (default 3 → up to 300 works/query), {@code TOPIC_PAGES} (default 10 →
 * up to 1000 breadth works), {@code CREDIT_CEILING} (default 0.50), {@code DRY_RUN}.
 */
public final class NtCorpusBuilder {

    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * Targeted queries, one per open/measurement object in the vocabulary (mirrors
     * ScalarOpenness.PRIORITY_TARGETS / MEASUREMENT_FRAMES — comments name the object).
     */
    static final List<String> SEED_QUERIES = List.of(
            "Dirichlet divisor problem error term",              // dirichlet_error
            "Cramér conjecture maximal prime gaps",              // max_prime_gap / cramer_gap_ratio
            "Hardy-Littlewood twin prime constant",              // twin_prime_density / twin_vs_prime_sq
            "Erdős Kac theorem number of prime factors",         // omega_ek_ratio
            "Mertens function bounds",                           // mertens_delta / mertens_per_sqrt_W
            "average order divisor function Euler-Mascheroni",   // mean_divisor_excess → 2γ
            "Wigert maximal order divisor function",             // max_divisor_count / wigert_bound
            "density of abundant numbers",                       // abundant_density (Deléglise)
            "squarefree numbers density",                        // squarefree_density → 6/π²
            "sum over primes 1/(p(p-1)) prime powers",           // omega_Omega_gap → 0.77316
            "normal order omega(n) Hardy Ramanujan",             // mean_omega / mean_Omega
            "prime counting function error term Riemann",        // prime_density / Schoenfeld
            "large gaps between consecutive primes numerical",   // max_prime_gap (computational)
            "divisor summatory function Voronoi",                // dirichlet_error (methods)
            "distribution of deficient and perfect numbers",     // deficient_density
            "Mertens theorems products over primes");            // B constants / log log sums

    /** Analytic Number Theory topic id, probed live 2026-07-04 via /topics?search=number theory. */
    static final String NT_TOPIC_ID = "T10061|T11166";   // Algebraic Geometry & NT + Analytic NT

    private static final String SELECT_FIELDS =
            "id,doi,title,publication_year,cited_by_count,primary_location,authorships,"
                    + "primary_topic,abstract_inverted_index";

    /**
     * 2026-07-08 subject-gate build support: constrain the SEED_QUERIES (which are otherwise
     * field-unconstrained OpenAlex full-text searches, so a phrase like "density of abundant
     * numbers" pulls ecology/neuroscience/fluids papers sharing the words) to the Algebra &
     * Number Theory subfield. Env-overridable — VERIFY the id against /subfields?search=number
     * theory before the rebuild (OpenAlex subfield "Algebra and Number Theory"). The breadth
     * pull is already topic-scoped, so this only tightens the seed pull.
     */
    static final String SEED_SUBFIELD_FILTER =
            env("OPENALEX_SEED_SUBFIELD", "primary_topic.subfield.id:2602");

    public static void main(String[] args) throws Exception {
        String corpusDir = env("CORPUS_DIR",
                System.getProperty("user.home") + "/.emergentterrain/corpus-nt");
        int pagesPerQuery = Integer.parseInt(env("PAGES_PER_QUERY", "3"));
        int topicPages = Integer.parseInt(env("TOPIC_PAGES", "10"));
        double ceiling = Double.parseDouble(env("CREDIT_CEILING", "0.50"));
        boolean dryRun = Boolean.parseBoolean(env("DRY_RUN", "false"));

        Path dir = Path.of(corpusDir);
        Files.createDirectories(dir);
        OpenAlexCreditLedger ledger =
                new OpenAlexCreditLedger(dir.resolve("openalex-ledger.json"), ceiling);
        OpenAlexResponseCache cache =
                new OpenAlexResponseCache(dir.resolve("openalex-cache.jsonl"));
        OpenAlexClient client = new OpenAlexClient(
                env("OPENALEX_BASE_URL", ""), env("OPENALEX_API_KEY", ""),
                env("OPENALEX_MAILTO", ""), ledger, cache);

        Path abstractsFile = dir.resolve("abstracts.jsonl");
        Set<String> seenIds = loadSeenIds(abstractsFile);
        System.out.printf("nt-corpus build — dir=%s existing=%d ceiling=$%.2f spentToday=$%.4f%n",
                dir, seenIds.size(), ceiling, ledger.spentTodayUsd());
        if (dryRun) {
            System.out.println("DRY_RUN=true — would run " + SEED_QUERIES.size()
                    + " seed queries × " + pagesPerQuery + " pages + topic pull × " + topicPages
                    + " pages. Exiting.");
            return;
        }

        ArrayNode manifestQueries = OM.createArrayNode();
        int added = 0;
        try {
            // 1) Targeted seed queries (SEARCH class, $0.001/page).
            for (String query : SEED_QUERIES) {
                int got = pullPages(client, abstractsFile, seenIds, query,
                        Map.of("filter", "has_abstract:true," + SEED_SUBFIELD_FILTER),
                        pagesPerQuery, query);
                manifestQueries.add(OM.createObjectNode().put("query", query).put("added", got));
                added += got;
                System.out.printf("  seed '%s' → +%d (total %d, spent $%.4f)%n",
                        query, got, seenIds.size(), ledger.spentTodayUsd());
            }
            // 2) Breadth pull: most-cited works in the NT topics (LIST class, $0.0001/page).
            int got = pullPages(client, abstractsFile, seenIds, null,
                    Map.of("filter", "primary_topic.id:" + NT_TOPIC_ID + ",has_abstract:true",
                            "sort", "cited_by_count:desc"),
                    topicPages, "topic:" + NT_TOPIC_ID);
            manifestQueries.add(OM.createObjectNode().put("query", "topic:" + NT_TOPIC_ID)
                    .put("added", got));
            added += got;
            System.out.printf("  topic pull → +%d (total %d, spent $%.4f)%n",
                    got, seenIds.size(), ledger.spentTodayUsd());
        } catch (OpenAlexCreditLedger.BudgetExceededException e) {
            System.out.println("BUDGET STOP — " + e.getMessage());
            System.out.println("Progress is persisted; re-run tomorrow (or raise CREDIT_CEILING) "
                    + "to continue — completed pages are cached and free.");
        }

        writeManifest(dir, manifestQueries, seenIds.size(), added, ledger);
        System.out.printf("done — corpus now %d abstracts (+%d this run), spent $%.4f / $%.2f, "
                        + "%d API calls today%n",
                seenIds.size(), added, ledger.spentTodayUsd(), ceiling, ledger.callsToday());
    }

    /** Cursor-paginate one query/filter; append new works to abstracts.jsonl; return #added. */
    private static int pullPages(OpenAlexClient client, Path abstractsFile, Set<String> seenIds,
                                 String search, Map<String, String> baseParams, int maxPages,
                                 String provenance) throws IOException {
        int added = 0;
        String cursor = "*";
        for (int page = 0; page < maxPages && cursor != null; page++) {
            Map<String, String> params = new LinkedHashMap<>(baseParams);
            params.put("select", SELECT_FIELDS);
            params.put("per-page", "100");
            params.put("cursor", cursor);
            JsonNode response = search != null
                    ? client.searchWorks(search, params)
                    : client.listWorks(params);
            for (JsonNode work : response.path("results")) {
                String id = work.path("id").asText("");
                if (id.isBlank() || !seenIds.add(id)) {
                    continue;
                }
                appendWork(abstractsFile, work, provenance);
                added++;
            }
            JsonNode next = response.path("meta").path("next_cursor");
            cursor = next.isMissingNode() || next.isNull() ? null : next.asText(null);
        }
        return added;
    }

    private static void appendWork(Path abstractsFile, JsonNode work, String provenance)
            throws IOException {
        ObjectNode row = OM.createObjectNode();
        row.put("id", work.path("id").asText());
        row.put("doi", work.path("doi").asText(""));
        row.put("title", work.path("title").asText(""));
        row.put("year", work.path("publication_year").asInt(0));
        row.put("cited_by_count", work.path("cited_by_count").asInt(0));
        row.put("venue", work.path("primary_location").path("source")
                .path("display_name").asText(""));
        ArrayNode authors = row.putArray("authors");
        for (JsonNode a : work.path("authorships")) {
            String name = a.path("author").path("display_name").asText("");
            if (!name.isBlank() && authors.size() < 8) {
                authors.add(name);
            }
        }
        // Subject tags for the retrieval-time subject gate (literature_grounding.jl):
        // field display name + subfield id + topic id from OpenAlex primary_topic.
        JsonNode pt = work.path("primary_topic");
        row.put("field", pt.path("field").path("display_name").asText(""));
        row.put("subfield", lastPathSegment(pt.path("subfield").path("id").asText("")));
        row.put("topic", lastPathSegment(pt.path("id").asText("")));
        row.put("abstract",
                OpenAlexClient.reconstructAbstract(work.path("abstract_inverted_index")));
        row.put("query", provenance);
        Files.writeString(abstractsFile, row.toString() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static Set<String> loadSeenIds(Path abstractsFile) {
        Set<String> ids = new HashSet<>();
        if (!Files.exists(abstractsFile)) {
            return ids;
        }
        try (BufferedReader r = Files.newBufferedReader(abstractsFile)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    ids.add(OM.readTree(line).path("id").asText());
                } catch (Exception ignored) { }
            }
        } catch (IOException ignored) { }
        return ids;
    }

    private static void writeManifest(Path dir, ArrayNode queries, int total, int added,
                                      OpenAlexCreditLedger ledger) throws IOException {
        ObjectNode manifest = OM.createObjectNode();
        manifest.put("built_at", Instant.now().toString());
        manifest.put("total_abstracts", total);
        manifest.put("added_this_run", added);
        manifest.put("spent_today_usd", ledger.spentTodayUsd());
        manifest.put("ceiling_usd", ledger.dailyCeilingUsd());
        manifest.set("queries", queries);
        Files.writeString(dir.resolve("MANIFEST.json"), manifest.toPrettyString());
    }

    /** OpenAlex ids arrive as URLs (…/subfields/2602, …/T11166); keep the short trailing id. */
    private static String lastPathSegment(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private NtCorpusBuilder() {}
}
