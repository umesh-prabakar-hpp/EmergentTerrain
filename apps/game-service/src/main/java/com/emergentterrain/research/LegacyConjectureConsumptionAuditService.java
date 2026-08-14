package com.emergentterrain.research;

import com.emergentterrain.research.conjecture.Conjecture;
import com.emergentterrain.researchcontract.ResearchConjectureConsumptionEvent;
import com.emergentterrain.researchcontract.ResearchConjectureConsumptionWindow;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final ConcurrentHashMap<UUID, RoomJournal> journals = new ConcurrentHashMap<>();

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
        RoomJournal journal = journal(roomId);
        try {
            String sourceId = conjecture.id() == null ? "UNRESOLVED_SOURCE" : conjecture.id().toString();
            UUID eventId = UUID.nameUUIDFromBytes(("legacy-conjecture-consumption:" + roomId + ":" + gameTick
                    + ":" + author.getBotId() + ":" + selectionKind + ":" + sourceId).getBytes(StandardCharsets.UTF_8));
            synchronized (journal) {
                ResearchConjectureConsumptionEvent event = new ResearchConjectureConsumptionEvent(eventId, roomId,
                        journal.nextSequence(), gameTick, author.getBotId(), author.getDomain(), sourceId,
                        selectionKind, Instant.now());
                if (writeEvent(event)) {
                    journal.append(event);
                }
            }
        } catch (IOException serializationOrJournalFailure) {
            log.warn("Unable to record legacy conjecture-consumption audit for room={} tick={}; continuing legacy cycle",
                    roomId, gameTick, serializationOrJournalFailure);
        }
    }

    /** Returns only completed, post-consumption observations; a new epoch makes restarts explicit. */
    public ResearchConjectureConsumptionWindow windowForRoom(UUID roomId) {
        return enabled ? journal(roomId).window() : null;
    }

    private boolean writeEvent(ResearchConjectureConsumptionEvent event) throws IOException {
        Path roomDirectory = journalRoot.resolve(event.roomId().toString());
        Files.createDirectories(roomDirectory);
        Path destination = roomDirectory.resolve(event.eventId() + ".json");
        Path staging = roomDirectory.resolve("." + event.eventId() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(staging, objectMapper.writeValueAsString(event), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            // Same-directory move publishes a fully written file and, without REPLACE_EXISTING,
            // fails closed on an exact/concurrent replay instead of rewriting the first event.
            Files.move(staging, destination);
            return true;
        } catch (FileAlreadyExistsException exactReplay) {
            // The deterministic event id makes retries and restarts idempotent without rewriting history.
            return false;
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private RoomJournal journal(UUID roomId) {
        return journals.computeIfAbsent(roomId, ignored -> new RoomJournal());
    }

    private static final class RoomJournal {
        private static final int MAX_EVENTS_PER_ROOM = 128;

        private final String epoch = UUID.randomUUID().toString();
        private final Deque<ResearchConjectureConsumptionEvent> events = new ArrayDeque<>();
        private long nextSequence = 1L;

        synchronized long nextSequence() {
            return nextSequence;
        }

        synchronized void append(ResearchConjectureConsumptionEvent event) {
            events.addLast(event);
            nextSequence++;
            while (events.size() > MAX_EVENTS_PER_ROOM) {
                events.removeFirst();
            }
        }

        synchronized ResearchConjectureConsumptionWindow window() {
            if (events.isEmpty()) {
                return new ResearchConjectureConsumptionWindow(epoch, 0L, 0L, List.of());
            }
            ResearchConjectureConsumptionEvent first = events.getFirst();
            ResearchConjectureConsumptionEvent latest = events.getLast();
            return new ResearchConjectureConsumptionWindow(epoch, first.sequence(), latest.sequence(),
                    List.copyOf(events));
        }
    }
}
