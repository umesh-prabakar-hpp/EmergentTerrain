package com.emergentterrain.research.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 32 §32.5 step 1 (phase-32-openalex-corpus.md item A) — append-only JSONL response cache
 * (the ARC retrieval-cache pattern) so resumed corpus builds and repeat probes never re-pay
 * OpenAlex credits: a byte-identical repeat request is a cache hit, zero network, zero spend.
 *
 * <p>One JSON object per line: {@code {key, ts, body}}. The whole file is loaded into memory on
 * first use — corpus-build scale is ~10³ list pages + ~10² content fetches, well within budget.
 * Keys MUST be pre-sanitized by the caller ({@link OpenAlexClient} strips the {@code api_key}
 * parameter before deriving the key) so the secret never reaches disk.
 */
public class OpenAlexResponseCache {

    private static final ObjectMapper OM = new ObjectMapper();

    private final Path jsonlFile;
    private Map<String, String> entries;   // key -> body; lazy-loaded

    public OpenAlexResponseCache(Path jsonlFile) {
        this.jsonlFile = jsonlFile;
    }

    public synchronized Optional<String> get(String key) {
        loadIfNeeded();
        return Optional.ofNullable(entries.get(key));
    }

    public synchronized void put(String key, String body) {
        loadIfNeeded();
        if (entries.containsKey(key)) {
            return;
        }
        entries.put(key, body);
        if (jsonlFile == null) {
            return;
        }
        try {
            ObjectNode node = OM.createObjectNode();
            node.put("key", key);
            node.put("ts", Instant.now().toString());
            node.put("body", body);
            Files.createDirectories(jsonlFile.toAbsolutePath().getParent());
            Files.writeString(jsonlFile, node.toString() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Cache persistence is best-effort — a failed append costs credits on the next run,
            // never correctness.
        }
    }

    public synchronized int size() {
        loadIfNeeded();
        return entries.size();
    }

    private void loadIfNeeded() {
        if (entries != null) {
            return;
        }
        entries = new HashMap<>();
        if (jsonlFile == null || !Files.exists(jsonlFile)) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(jsonlFile)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode node = OM.readTree(line);
                    entries.put(node.path("key").asText(), node.path("body").asText());
                } catch (Exception ignored) {
                    // Skip a corrupt line; later lines still load.
                }
            }
        } catch (IOException e) {
            // Unreadable cache = cold cache.
        }
    }
}
