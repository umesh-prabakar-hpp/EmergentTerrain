package com.emergentterrain.research;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 6 D1.2 — read-only access to the curated literature corpus.
 *
 * <p>Loads {@code literature-corpus.yml} from the classpath at startup and
 * exposes {@link #findFor(String, String)} (and a bounded {@link #findFor(String, String, int)}
 * variant) so future callers (D1.3's narrator integration; D2's Discussion
 * section) can splice references into prompts. This commit lands the corpus
 * + service only — no caller yet, no behaviour change.
 *
 * <p>The lookup is exact case-insensitive equality on {@code (domain, topic)}
 * per the D1.1 memo §6. Multiple entries for the same key are ranked by
 * {@code priority} descending, then alphabetical {@code citation} ascending.
 */
@Service
public class LiteratureSearchService {

    private static final Logger log = LoggerFactory.getLogger(LiteratureSearchService.class);

    /** Default cap on references returned by {@link #findFor(String, String)} per §3.2 of the memo. */
    static final int DEFAULT_MAX_RESULTS = 3;

    private final Resource corpusResource;
    private volatile List<LiteratureReference> references = List.of();

    public LiteratureSearchService(
            @Value("classpath:literature-corpus.yml") Resource corpusResource) {
        this.corpusResource = corpusResource;
    }

    @PostConstruct
    void loadCorpus() {
        try (InputStream in = corpusResource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) {
                log.warn("literature-corpus.yml is empty");
                return;
            }
            Object refs = root.get("references");
            if (!(refs instanceof List<?> list)) {
                log.warn("literature-corpus.yml missing 'references:' top-level list");
                return;
            }
            List<LiteratureReference> loaded = new ArrayList<>();
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> m)) continue;
                loaded.add(new LiteratureReference(
                        str(m, "domain"),
                        str(m, "topic"),
                        str(m, "citation"),
                        str(m, "closed_form"),
                        str(m, "valid_region"),
                        str(m, "magnitude_estimate"),
                        str(m, "notes"),
                        intOr(m, "priority", 0)));
            }
            this.references = List.copyOf(loaded);
            log.info("LiteratureSearchService: loaded {} reference(s) from {}",
                    loaded.size(), corpusResource.getDescription());
        } catch (Exception e) {
            log.error("LiteratureSearchService: failed to load corpus from {}: {}",
                    corpusResource.getDescription(), e.getMessage(), e);
            // Fail-open: the service keeps an empty corpus so callers degrade
            // to "no literature found" rather than crashing the application.
        }
    }

    /**
     * Returns up to {@link #DEFAULT_MAX_RESULTS} references for the given
     * (domain, topic), ranked by priority descending then alphabetical citation.
     * Empty when the corpus has no entries for the key.
     */
    public List<LiteratureReference> findFor(String domain, String topic) {
        return findFor(domain, topic, DEFAULT_MAX_RESULTS);
    }

    /**
     * Returns up to {@code maxResults} references for the given (domain, topic).
     * Caller can request more (e.g. D2's related-work section may want > 3) or
     * fewer. Negative or zero {@code maxResults} returns an empty list.
     */
    public List<LiteratureReference> findFor(String domain, String topic, int maxResults) {
        if (domain == null || topic == null || maxResults <= 0) return List.of();
        return references.stream()
                .filter(r -> equalsIgnoreCase(domain, r.domain()))
                .filter(r -> equalsIgnoreCase(topic, r.topic()))
                .sorted(Comparator
                        .comparingInt(LiteratureReference::priority).reversed()
                        .thenComparing(r -> r.citation() != null ? r.citation() : ""))
                .limit(maxResults)
                .toList();
    }

    /** Total number of references currently loaded — used by tests + telemetry. */
    public int size() {
        return references.size();
    }

    /** Snapshot of the loaded references — used by tests; never mutated. */
    List<LiteratureReference> allReferences() {
        return references;
    }

    // ── helpers ──

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intOr(Map<?, ?> m, String key, int fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) {}
        }
        return fallback;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) return a == b;
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
