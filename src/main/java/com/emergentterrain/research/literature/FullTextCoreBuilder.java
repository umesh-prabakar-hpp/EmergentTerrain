package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Phase 33 lane L (L1, 2026-07-05) — offline full-text core builder. For the works nearest the
 * live families ({@link NtCorpusBuilder#SEED_QUERIES}, same 16 objects), pulls the open-access
 * PDF named by OpenAlex ({@code best_oa_location.pdf_url}), extracts plain text with PDFBox, and
 * stores one {@code cores/<workId>.txt} per work plus a {@code cores/MANIFEST.json}. This is the
 * asset lane L2's {@code read_reference} tool reads from — the difference between "a match
 * exists" (abstract snippet) and engaging a prior result's actual content.
 *
 * <p>Costs: OpenAlex SEARCH pages via the existing client + credit ledger (~$0.001/page,
 * negligible); the PDFs themselves come from publishers/arXiv (no OpenAlex credits) with polite
 * pacing, a size cap, and per-host failure tolerance. Idempotent: works already in the manifest
 * are skipped, so re-runs only fill gaps.
 *
 * <p>Run OFFLINE via {@code scripts/build-fulltext-cores.sh} (compiles — never run while the app
 * JVM is live; see project_app_runs_from_target_classes).
 */
public final class FullTextCoreBuilder {

    private static final ObjectMapper OM = new ObjectMapper();

    static final int MAX_PDF_BYTES = 20 * 1024 * 1024;
    static final int MAX_PAGES = 40;
    static final int MAX_TEXT_CHARS = 200_000;
    static final int MIN_TEXT_CHARS = 2_000;   // shorter = extraction failed / stub page
    private static final long DOWNLOAD_PACING_MS = 400;

    private static final String SELECT_FIELDS =
            "id,doi,title,publication_year,cited_by_count,best_oa_location,authorships";

    public static void main(String[] args) throws Exception {
        String corpusDir = env("CORPUS_DIR",
                System.getProperty("user.home") + "/.emergentterrain/corpus-nt");
        int targetCores = Integer.parseInt(env("TARGET_CORES", "350"));
        int pagesPerQuery = Integer.parseInt(env("PAGES_PER_QUERY", "2"));
        double ceiling = Double.parseDouble(env("CREDIT_CEILING", "0.50"));
        boolean dryRun = Boolean.parseBoolean(env("DRY_RUN", "false"));

        Path coresDir = Path.of(corpusDir).resolve("cores");
        Files.createDirectories(coresDir);
        Path manifestFile = coresDir.resolve("MANIFEST.json");
        ObjectNode manifest = Files.isRegularFile(manifestFile)
                ? (ObjectNode) OM.readTree(Files.readString(manifestFile))
                : OM.createObjectNode();
        ArrayNode works = manifest.withArray("works");
        Set<String> have = new HashSet<>();
        works.forEach(w -> have.add(w.path("id").asText()));

        OpenAlexCreditLedger ledger = new OpenAlexCreditLedger(
                Path.of(corpusDir).resolve("openalex-ledger.json"), ceiling);
        OpenAlexResponseCache cache = new OpenAlexResponseCache(
                Path.of(corpusDir).resolve("openalex-cache.jsonl"));
        OpenAlexClient client = new OpenAlexClient(
                env("OPENALEX_BASE_URL", ""), env("OPENALEX_API_KEY", ""),
                env("OPENALEX_MAILTO", ""), ledger, cache);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        System.out.printf("fulltext-cores build — dir=%s existing=%d target=%d spentToday=$%.4f%n",
                coresDir, have.size(), targetCores, ledger.spentTodayUsd());
        if (dryRun) {
            System.out.println("DRY_RUN=true — would sweep " + NtCorpusBuilder.SEED_QUERIES.size()
                    + " queries × " + pagesPerQuery + " pages for OA PDFs. Exiting.");
            return;
        }

        int fetched = 0;
        int failed = 0;
        outer:
        for (String query : NtCorpusBuilder.SEED_QUERIES) {
            String cursor = "*";
            for (int page = 0; page < pagesPerQuery && cursor != null; page++) {
                Map<String, String> params = new HashMap<>();
                params.put("select", SELECT_FIELDS);
                params.put("per-page", "25");
                params.put("cursor", cursor);
                params.put("filter", "open_access.is_oa:true");
                JsonNode response = client.searchWorks(query, params);
                for (JsonNode work : response.path("results")) {
                    if (have.size() >= targetCores) {
                        break outer;
                    }
                    String id = shortId(work.path("id").asText(""));
                    String pdfUrl = work.path("best_oa_location").path("pdf_url").asText("");
                    if (id.isBlank() || pdfUrl.isBlank() || have.contains(id)) {
                        continue;
                    }
                    try {
                        Thread.sleep(DOWNLOAD_PACING_MS);
                        String text = fetchAndExtract(http, pdfUrl,
                                env("OPENALEX_MAILTO", "et@example.org"));
                        if (text == null || text.length() < MIN_TEXT_CHARS) {
                            failed++;
                            continue;
                        }
                        Files.writeString(coresDir.resolve(id + ".txt"), text);
                        works.add(coreEntry(work, id, pdfUrl, text.length(), query));
                        have.add(id);
                        fetched++;
                        if (fetched % 20 == 0) {
                            Files.writeString(manifestFile, manifest.toPrettyString());
                            System.out.printf("  … %d cores (%d failed), spent $%.4f%n",
                                    have.size(), failed, ledger.spentTodayUsd());
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break outer;
                    } catch (Exception e) {
                        failed++;   // one bad PDF/host never stops the sweep
                    }
                }
                JsonNode next = response.path("meta").path("next_cursor");
                cursor = next.isMissingNode() || next.isNull() ? null : next.asText(null);
            }
            System.out.printf("  query '%s' done — cores=%d failed=%d%n", query, have.size(), failed);
        }

        manifest.put("builtAt", java.time.Instant.now().toString());
        manifest.put("totalCores", have.size());
        manifest.put("failedFetches", failed);
        Files.writeString(manifestFile, manifest.toPrettyString());
        System.out.printf("fulltext-cores done — cores=%d failed=%d spentToday=$%.4f manifest=%s%n",
                have.size(), failed, ledger.spentTodayUsd(), manifestFile);
    }

    /** Download one PDF (size-capped) and extract normalized plain text; null on any failure. */
    static String fetchAndExtract(HttpClient http, String pdfUrl, String mailto) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(pdfUrl))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "EmergentTerrain-corpus/1.0 (mailto:" + mailto + ")")
                .GET().build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200 || resp.body() == null
                || resp.body().length == 0 || resp.body().length > MAX_PDF_BYTES) {
            return null;
        }
        // Cheap magic check — some "pdf_url"s serve HTML landing pages.
        byte[] body = resp.body();
        if (body.length < 5 || body[0] != '%' || body[1] != 'P' || body[2] != 'D' || body[3] != 'F') {
            return null;
        }
        try (PDDocument doc = Loader.loadPDF(body)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(MAX_PAGES, doc.getNumberOfPages()));
            return normalize(stripper.getText(doc));
        }
    }

    /** Collapse extraction whitespace artifacts and cap the length for the core file. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace("\r\n", "\n")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return t.length() > MAX_TEXT_CHARS ? t.substring(0, MAX_TEXT_CHARS) : t;
    }

    /** "https://openalex.org/W123" → "W123" (the core filename + manifest key). */
    static String shortId(String openAlexId) {
        if (openAlexId == null) {
            return "";
        }
        int slash = openAlexId.lastIndexOf('/');
        String id = slash >= 0 ? openAlexId.substring(slash + 1) : openAlexId;
        return id.matches("[A-Za-z0-9]+") ? id : "";
    }

    private static ObjectNode coreEntry(JsonNode work, String id, String pdfUrl,
                                        int chars, String query) {
        ObjectNode e = OM.createObjectNode();
        e.put("id", id);
        e.put("doi", work.path("doi").asText(""));
        e.put("title", work.path("title").asText(""));
        e.put("year", work.path("publication_year").asInt(0));
        e.put("cited_by", work.path("cited_by_count").asInt(0));
        e.put("chars", chars);
        e.put("pdf_url", pdfUrl);
        e.put("query", query);
        return e;
    }

    private static String env(String key, String dflt) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? dflt : v;
    }

    private FullTextCoreBuilder() {}
}
