package com.emergentterrain.research;

import com.emergentterrain.research.conjecture.Conjecture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/**
 * Default-off game-owned journal of legacy conjecture consumption. It observes only a successful
 * legacy mark and must never influence queue selection, mutation, handoff, or paper workflow.
 */
@Service
public class LegacyConjectureConsumptionAuditService {
    private static final Logger log = LoggerFactory.getLogger(LegacyConjectureConsumptionAuditService.class);

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Path journalRoot;

    public LegacyConjectureConsumptionAuditService(ObjectMapper objectMapper,
                                                   @Value("${app.research.conjecture-consumption-audit.enabled:false}") boolean enabled,
                                                   @Value("${app.research.conjecture-consumption-audit.journal-root:simulation-history/research-queue-consumption}") String journalRoot) {
        this(objectMapper, enabled, Path.of(journalRoot));
    }

    LegacyConjectureConsumptionAuditService(ObjectMapper objectMapper, boolean enabled, Path journalRoot) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.journalRoot = journalRoot;
    }

    /** Records a post-consumption observation once; journal failures are deliberately non-fatal to gameplay. */
    public void record(UUID roomId, long gameTick, Researcher author, Conjecture conjecture, String selectionKind) {
        if (!enabled) return;
        try {
            String sourceId = conjecture.id() == null ? "UNRESOLVED_SOURCE" : conjecture.id().toString();
            UUID eventId = UUID.nameUUIDFromBytes(("legacy-conjecture-consumption:" + roomId + ":" + gameTick
                    + ":" + author.getBotId() + ":" + selectionKind + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
            LegacyConjectureConsumptionAudit event = new LegacyConjectureConsumptionAudit(eventId, roomId, gameTick,
                    author.getBotId(), author.getDomain(), sourceId, selectionKind, Instant.now());
            Path roomDirectory = journalRoot.resolve(roomId.toString());
            Files.createDirectories(roomDirectory);
            Path destination = roomDirectory.resolve(eventId + ".json");
            Path staging = roomDirectory.resolve("." + eventId + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(staging, objectMapper.writeValueAsString(event), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                // Same-directory move publishes a fully written file and, without REPLACE_EXISTING,
                // fails closed on an exact/concurrent replay instead of rewriting the first event.
                Files.move(staging, destination);
            } catch (FileAlreadyExistsException exactReplay) {
                // The deterministic event id makes retries and restarts idempotent without rewriting history.
            } finally {
                Files.deleteIfExists(staging);
            }
        } catch (IOException serializationOrJournalFailure) {
            log.warn("Unable to record legacy conjecture-consumption audit for room={} tick={}; continuing legacy cycle",
                    roomId, gameTick, serializationOrJournalFailure);
        }
    }

    record LegacyConjectureConsumptionAudit(UUID eventId, UUID roomId, long gameTick, String authorBotId,
                                            String domain, String sourceId, String selectionKind, Instant observedAt) { }
}
