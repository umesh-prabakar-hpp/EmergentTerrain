package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Phase 33 lane L (L2 supply-side, 2026-07-06) — EVIDENCE-driven core backfill. Run-33's
 * root cause: only ~2% of the 99k-work corpus has full-text cores, so a professor's
 * read_reference attempt on an EVIDENCE match usually misses (P-815's did) and a 5-round
 * budget rationally prefers verification. This job closes the loop from the demand side:
 * scan the research output (papers + llm-traces) for the [W…] keys search_literature
 * actually returned, and fetch ar5iv HTML cores for exactly those works — converting the
 * hit rate on RECURRING matches to near-certainty.
 * It also consumes non-evidentiary OpenAlex candidates from the durable Phase 35 timeout
 * recovery journal. Those candidates are discovery/backfill inputs only: the subsequent offline
 * import and normal local search must still issue an HF key before a paper can cite them.
 *
 * <p>Post-run / nightly, NEVER while the app is live (mvn compile under a live JVM —
 * [[project_app_runs_from_target_classes]]). Reuses the L1b extraction pipeline verbatim
 * ({@link ArxivHtmlCoreBuilder#fetch}/{@link ArxivHtmlCoreBuilder#extract} with the
 * math-sentinel-before-tag-strip invariant) and the shared metered OpenAlex client for the
 * one /works/&lt;id&gt; lookup each missing key needs. Manifest entries carry
 * {@code source=evidence-backfill}. Idempotent; per-work failure tolerance; manifest written
 * in {@code finally}.
 */
public final class EvidenceCoreBackfill {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final long PACING_MS = 1_200;

    /** OpenAlex work keys as they appear in grounded tool results ("[W2909857905]"). */
    static final Pattern WORK_KEY = Pattern.compile("\\bW\\d{7,}\\b");

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(env("CORPUS_DIR",
                System.getProperty("user.home") + "/.emergentterrain/corpus-nt"));
        Path research = Path.of(env("RESEARCH_DIR", "research"));
        Path recovery = Path.of(env("LITERATURE_RECOVERY_DIR",
                "simulation-history/research-literature-recovery"));
        Path out = corpus.resolve("cores-html");
        Files.createDirectories(out);
        Path pdfDir = corpus.resolve("cores");

        Set<String> wanted = scanKeys(research);
        wanted.addAll(scanRecoveryCandidates(recovery));
        Set<String> missing = new LinkedHashSet<>();
        for (String id : wanted) {
            if (!Files.isRegularFile(out.resolve(id + ".txt"))
                    && !Files.isRegularFile(pdfDir.resolve(id + ".txt"))) {
                missing.add(id);
            }
        }
        System.out.printf("evidence-backfill — keys seen=%d already covered=%d missing=%d%n",
                wanted.size(), wanted.size() - missing.size(), missing.size());
        if (missing.isEmpty()) {
            return;
        }

        Path outManifestFile = out.resolve("MANIFEST.json");
        ObjectNode manifest = Files.isRegularFile(outManifestFile)
                ? (ObjectNode) OM.readTree(Files.readString(outManifestFile))
                : OM.createObjectNode();
        ArrayNode works = manifest.withArray("works");

        OpenAlexCreditLedger ledger = new OpenAlexCreditLedger(
                corpus.resolve("openalex-ledger.json"),
                Double.parseDouble(env("CREDIT_CEILING", "0.50")));
        OpenAlexResponseCache cache = new OpenAlexResponseCache(
                corpus.resolve("openalex-cache.jsonl"));
        OpenAlexClient client = new OpenAlexClient(
                env("OPENALEX_BASE_URL", ""), env("OPENALEX_API_KEY", ""),
                env("OPENALEX_MAILTO", ""), ledger, cache);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String mailto = env("OPENALEX_MAILTO", "et@example.org");

        int fetched = 0;
        int noArxiv = 0;
        int failed = 0;
        try {
            for (String id : missing) {
                try {
                    Thread.sleep(PACING_MS);
                    JsonNode work = client.getWork(id);
                    if (work == null || work.isMissingNode()) {
                        failed++;
                        continue;
                    }
                    String arxivId = ArxivHtmlCoreBuilder.arxivIdFromLocations(work);
                    if (arxivId == null) {
                        noArxiv++;   // not arXiv-hosted — no free full text; skip honestly
                        continue;
                    }
                    String html = ArxivHtmlCoreBuilder.fetch(http, arxivId, mailto);
                    String text = html == null ? null : ArxivHtmlCoreBuilder.extract(html);
                    if (text == null || text.length() < ArxivHtmlCoreBuilder.MIN_TEXT_CHARS) {
                        failed++;
                        continue;
                    }
                    Files.writeString(out.resolve(id + ".txt"), text);
                    ObjectNode e = OM.createObjectNode();
                    e.put("id", id);
                    e.put("arxiv_id", arxivId);
                    e.put("title", work.path("title").asText(""));
                    e.put("year", work.path("publication_year").asInt(0));
                    e.put("chars", text.length());
                    e.put("source", "evidence-backfill");
                    works.add(e);
                    fetched++;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    failed++;
                    System.err.println("  " + id + " failed: " + e.getMessage());
                }
            }
        } finally {
            Files.writeString(outManifestFile, manifest.toPrettyString());
        }
        System.out.printf("evidence-backfill done — fetched=%d not-arxiv=%d failed=%d "
                + "(cores-html now %d works)%n", fetched, noArxiv, failed, works.size());
    }

    /**
     * All work keys in files that mention search_literature, across papers and llm-traces
     * (traces carry the untruncated tool results). Insertion-ordered; pure scan, no network.
     */
    static Set<String> scanKeys(Path research) throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        if (!Files.isDirectory(research)) {
            return keys;
        }
        try (Stream<Path> walk = Files.walk(research)) {
            walk.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".md") || n.endsWith(".jsonl");
            }).forEach(p -> {
                try {
                    String text = Files.readString(p);
                    if (!text.contains("search_literature")) {
                        return;
                    }
                    keys.addAll(extractKeys(text));
                } catch (Exception ignored) {
                    // unreadable file (binary, permission) — skip; scan is best-effort
                }
            });
        }
        return keys;
    }

    /**
     * Extracts OpenAlex work ids from durable timeout-recovery jobs that have
     * completed discovery. The journal is intentionally the only bridge here:
     * no app request writes a core or touches the immutable Phase 34 index.
     */
    static Set<String> scanRecoveryCandidates(Path recovery) throws Exception {
        Set<String> keys = new LinkedHashSet<>();
        if (!Files.isDirectory(recovery)) {
            return keys;
        }
        try (Stream<Path> files = Files.list(recovery)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            JsonNode root = OM.readTree(Files.readString(path));
                            if (!"AWAITING_INDEX_BACKFILL".equals(root.path("state").asText())) {
                                return;
                            }
                            root.path("candidates").forEach(candidate -> {
                                String id = candidate.path("workId").asText("");
                                if (WORK_KEY.matcher(id).matches()) keys.add(id);
                            });
                        } catch (Exception ignored) {
                            // A malformed recovery record cannot stop a nightly backfill.
                        }
                    });
        }
        return keys;
    }

    /** Pure key extraction, for tests. */
    static Set<String> extractKeys(String text) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = WORK_KEY.matcher(text);
        while (m.find()) {
            keys.add(m.group());
        }
        return keys;
    }

    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? dflt : v;
    }
}
