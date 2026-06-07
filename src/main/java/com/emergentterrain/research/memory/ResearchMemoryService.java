package com.emergentterrain.research.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 31 §31.1 — the capture API of the cross-run research memory.
 * Write-only in this wave (the shadow mode of the whole phase): deterministic
 * writers call {@link #capture}, nothing reads the table until 31.3.
 *
 * <p>Semantics (mem0 adoption): append-only ADD with strict content-hash
 * dedup — capturing an identical {@code (type, content)} reinforces the
 * existing row ({@code reinforcements++}, {@code lastAccessedAt} touch)
 * instead of inserting; the 31.1 path NEVER updates content or supersedes
 * (that is 31.2's consolidation job). Every mutation here is therefore
 * insert-or-reinforce — replay-safe by construction, which is the 31.1
 * acceptance bar ("row count stable on replay").
 *
 * <p>Best-effort contract throughout: capture failures log and return empty;
 * the research pipeline never depends on this service succeeding.
 */
@Service
public class ResearchMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ResearchMemoryService.class);

    /** One id per JVM run — mem0's `run` scope; survives nothing, by design. */
    private final String runId = "run-" + Instant.now().toString().replace(":", "")
            + "-" + UUID.randomUUID().toString().substring(0, 8);

    private final ResearchMemoryRepository repository;
    private final boolean enabled;

    public ResearchMemoryService(ResearchMemoryRepository repository,
            @Value("${app.research.memory.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
        if (enabled) {
            log.info("research-memory capture ENABLED (31.1 write-only) runId={}", runId);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Insert-or-reinforce one memory. Returns the row's id when captured
     * (new or reinforced), empty when the flag is off or capture failed.
     */
    public Optional<UUID> capture(String type, String content, String sourceRefs,
            String topic, String domain, UUID roomId, String botId) {
        return captureAt(type, content, sourceRefs, topic, domain, roomId, botId, null);
    }

    /**
     * 31.2's cursor-correct crystal write: capture with an explicit
     * {@code createdAt} (the consolidation cursor boundary). Null = now.
     */
    public Optional<UUID> captureAt(String type, String content, String sourceRefs,
            String topic, String domain, Instant createdAt) {
        return captureAt(type, content, sourceRefs, topic, domain, null, null, createdAt);
    }

    private Optional<UUID> captureAt(String type, String content, String sourceRefs,
            String topic, String domain, UUID roomId, String botId, Instant createdAt) {
        if (!enabled || content == null || content.isBlank()) {
            return Optional.empty();
        }
        try {
            String hash = sha256(type + "\n" + content);
            Optional<ResearchMemoryEntity> existing = repository.findByContentHash(hash);
            if (existing.isPresent()) {
                ResearchMemoryEntity row = existing.get();
                row.reinforce();
                repository.save(row);
                return Optional.of(row.id());
            }
            ResearchMemoryEntity row = new ResearchMemoryEntity(
                    type, content, hash, sourceRefs, topic, domain, roomId, botId, runId);
            if (createdAt != null) {
                row.pinCreatedAt(createdAt);
            }
            try {
                repository.save(row);
            } catch (org.springframework.dao.DataIntegrityViolationException race) {
                // 2026-06-06 commit-review fix: concurrent identical captures —
                // the loser hits uq_memory_content_hash; convert to the
                // reinforce it semantically was, instead of losing the count.
                Optional<ResearchMemoryEntity> winner = repository.findByContentHash(hash);
                if (winner.isPresent()) {
                    winner.get().reinforce();
                    repository.save(winner.get());
                    return Optional.of(winner.get().id());
                }
                throw race;
            }
            return Optional.of(row.id());
        } catch (Exception e) {
            log.warn("research-memory capture failed (pipeline unaffected) type={}: {}",
                    type, e.getMessage());
            return Optional.empty();
        }
    }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
