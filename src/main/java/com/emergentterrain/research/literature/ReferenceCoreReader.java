package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 33 lane L (L2, 2026-07-06) — serves the full-text literature cores to the professor's
 * {@code read_reference} tool. The difference this exists to make: "prior work matches this
 * claim [W…]" (an abstract snippet) versus engaging the prior result's actual content
 * ("the cited work states θ ≤ 131/416; our windowed measurement is consistent with but far
 * weaker than that bound").
 *
 * <p>Contract (phase-33 L2, budget-safe by design — no global cap changes):
 * <ul>
 *   <li><b>Tier preference:</b> {@code cores-html/<id>.txt} (ar5iv — formulas are the author's
 *       original LaTeX) over {@code cores/<id>.txt} (PDF extraction — symbols may be lossy; the
 *       output carries a paraphrase-don't-quote caveat for this tier).</li>
 *   <li><b>Chunked:</b> within-document retrieval, Java-side — the document is split on
 *       paragraph boundaries and the top chunks matching the caller's query are returned, capped
 *       at ~{@value #MAX_RETURN_CHARS} chars (~2k tokens). No sidecar, no index, no memory
 *       growth.</li>
 *   <li><b>Failure-soft:</b> unknown key / missing file / unreadable manifest degrade to a
 *       descriptive message, never an exception.</li>
 * </ul>
 * Bean gated on {@code app.research.literature.read-reference.enabled}.
 */
@Component
@ConditionalOnProperty(value = "app.research.literature.read-reference.enabled",
        havingValue = "true")
public class ReferenceCoreReader {

    private static final Logger log = LoggerFactory.getLogger(ReferenceCoreReader.class);

    static final int MAX_RETURN_CHARS = 7_500;
    static final int MAX_CHUNK_CHARS = 2_600;
    static final int TOP_CHUNKS = 3;

    private final Path corpusDir;
    private final ObjectMapper om = new ObjectMapper();
    /** id → "Title (year)" from the two manifests; loaded lazily, best-effort. */
    private volatile Map<String, String> titles;

    public ReferenceCoreReader(
            @Value("${app.research.literature.cores-dir:${user.home}/.emergentterrain/corpus-nt}")
            String corpusDir) {
        this.corpusDir = Path.of(corpusDir);
    }

    /** True when at least one core tier exists on disk (gates tool advertising). */
    public boolean available() {
        return Files.isDirectory(corpusDir.resolve("cores-html"))
                || Files.isDirectory(corpusDir.resolve("cores"));
    }

    /**
     * The tool entry point: top passages of work {@code key} matching {@code query}.
     * Never throws.
     */
    public String read(String key, String query) {
        try {
            String id = key == null ? "" : key.strip();
            if (!id.matches("[A-Za-z0-9]+")) {
                return "read_reference: invalid key '" + key + "' — pass the [W…] key exactly as "
                        + "search_literature printed it (letters+digits only).";
            }
            Path htmlCore = corpusDir.resolve("cores-html").resolve(id + ".txt");
            Path pdfCore = corpusDir.resolve("cores").resolve(id + ".txt");
            boolean html = Files.isRegularFile(htmlCore);
            Path source = html ? htmlCore : pdfCore;
            if (!Files.isRegularFile(source)) {
                return "read_reference: no full-text core for " + id + " — the corpus holds "
                        + "abstracts for every indexed work but full text only for the open-access "
                        + "subset. Rely on the search_literature snippet for this one.";
            }
            String text = Files.readString(source);
            List<String> chunks = chunk(text);
            List<String> top = topChunks(chunks, query);
            StringBuilder sb = new StringBuilder();
            sb.append("read_reference ").append(id);
            String title = titleOf(id);
            if (title != null) {
                sb.append(" — ").append(title);
            }
            sb.append(html
                    ? " [source: ar5iv HTML — formulas are the author's original LaTeX, quotable]"
                    : " [source: PDF extraction — math symbols/structure may be lossy: PARAPHRASE "
                            + "prior results and cite the key; do NOT quote formulas verbatim from "
                            + "this text]");
            sb.append("\nTop passages matching \"").append(query == null ? "" : query).append("\":");
            for (String c : top) {
                sb.append("\n---\n").append(c);
            }
            return sb.length() > MAX_RETURN_CHARS
                    ? sb.substring(0, MAX_RETURN_CHARS) + "…" : sb.toString();
        } catch (Exception e) {
            log.warn("read_reference failed key={} (degrading to snippet-only): {}", key, e.getMessage());
            return "read_reference: could not read the core for " + key
                    + " — rely on the search_literature snippet.";
        }
    }

    /** Paragraph-boundary chunks, each ≤ {@value #MAX_CHUNK_CHARS} chars. Static for tests. */
    static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n\\s*\n")) {
            if (cur.length() + para.length() + 2 > MAX_CHUNK_CHARS && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            if (para.length() > MAX_CHUNK_CHARS) {
                for (int i = 0; i < para.length(); i += MAX_CHUNK_CHARS) {
                    out.add(para.substring(i, Math.min(para.length(), i + MAX_CHUNK_CHARS)));
                }
            } else {
                if (cur.length() > 0) {
                    cur.append("\n\n");
                }
                cur.append(para);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * Within-document retrieval: score = Σ over distinct query terms (len ≥ 3, lowercased) of
     * min(tf, 3) + a 2-point bonus per distinct term present — rewards chunks covering MANY
     * query terms over chunks repeating one. First chunk (title/abstract region) gets +1 as a
     * grounding anchor. Static + pure for tests.
     */
    static List<String> topChunks(List<String> chunks, String query) {
        String[] terms = (query == null ? "" : query).toLowerCase(Locale.ROOT)
                .split("[^a-z0-9]+");
        List<int[]> scored = new ArrayList<>();   // [score, index]
        for (int i = 0; i < chunks.size(); i++) {
            String lower = chunks.get(i).toLowerCase(Locale.ROOT);
            int score = i == 0 ? 1 : 0;
            for (String t : terms) {
                if (t.length() < 3) {
                    continue;
                }
                int tf = 0;
                int from = 0;
                while ((from = lower.indexOf(t, from)) >= 0 && tf < 3) {
                    tf++;
                    from += t.length();
                }
                if (tf > 0) {
                    score += tf + 2;
                }
            }
            scored.add(new int[]{score, i});
        }
        scored.sort((a, b) -> b[0] != a[0] ? Integer.compare(b[0], a[0])
                : Integer.compare(a[1], b[1]));
        List<String> out = new ArrayList<>();
        int budget = MAX_RETURN_CHARS - 500;   // header allowance
        for (int[] s : scored) {
            if (out.size() >= TOP_CHUNKS) {
                break;
            }
            String c = chunks.get(s[1]);
            if (budget - c.length() < 0) {
                continue;
            }
            budget -= c.length();
            out.add(c);
        }
        return out;
    }

    private String titleOf(String id) {
        Map<String, String> t = titles;
        if (t == null) {
            t = new HashMap<>();
            for (String tier : new String[]{"cores-html", "cores"}) {
                try {
                    Path mf = corpusDir.resolve(tier).resolve("MANIFEST.json");
                    if (!Files.isRegularFile(mf)) {
                        continue;
                    }
                    JsonNode works = om.readTree(Files.readString(mf)).path("works");
                    for (JsonNode w : works) {
                        t.putIfAbsent(w.path("id").asText(""),
                                "\"" + w.path("title").asText("") + "\" ("
                                        + w.path("year").asInt(0) + ")");
                    }
                } catch (Exception e) {
                    log.debug("manifest read failed for {}: {}", tier, e.getMessage());
                }
            }
            titles = t;
        }
        return t.get(id);
    }
}
