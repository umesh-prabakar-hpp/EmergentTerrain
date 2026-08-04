package com.emergentterrain.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Phase 4b — rate-limited cross-bot surprise broadcast.
 *
 * When a {@link SurpriseEvent} is recorded, this service decides whether to
 * "broadcast" it to other bots in the same room. The broadcast itself is just
 * a bounded in-memory buffer of recent events per room; a future LLM-prompt
 * builder or analytics surface can pull from {@link #recentBroadcasts} to
 * inject the most recent high-surprise findings into a neighbour's context.
 *
 * <p>The rate limit is a per-room cooldown: once a broadcast fires, further
 * broadcasts from the same room are dropped until the cooldown elapses. The
 * underlying surprise event is still persisted by
 * {@link ExpectationModelService} — only the broadcast fan-out is suppressed.
 *
 * <p>State is in-memory only. After a restart the cooldown is reset and the
 * recent buffer is empty; this matches the rest of the rate-limit machinery
 * in this codebase (FundingAllocationService uses the same pattern).
 *
 * <p>Gated by {@code app.research.surprise-broadcast.enabled} (default false).
 * When off, {@link #maybeBroadcast} is a no-op returning false and
 * {@link #recentBroadcasts} returns an empty list.
 */
@Service
public class SurpriseBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(SurpriseBroadcastService.class);

    /** Default cooldown duration in seconds; can be overridden via {@code app.research.surprise-broadcast.cooldown-seconds}. */
    static final long DEFAULT_COOLDOWN_SECONDS = 30;

    /** Maximum number of recent broadcasts retained per room. */
    private static final int MAX_RECENT_PER_ROOM = 5;

    private final boolean enabled;
    private final Duration cooldown;
    private final ConcurrentMap<UUID, Instant>             lastBroadcastByRoom = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Deque<SurpriseEvent>> recentByRoom        = new ConcurrentHashMap<>();

    public SurpriseBroadcastService(
            @Value("${app.research.surprise-broadcast.enabled:false}") boolean enabled,
            @Value("${app.research.surprise-broadcast.cooldown-seconds:30}") long cooldownSeconds) {
        this.enabled  = enabled;
        this.cooldown = Duration.ofSeconds(Math.max(0, cooldownSeconds));
        if (enabled) {
            log.info("SurpriseBroadcastService: ENABLED — high-surprise events broadcast "
                    + "to in-memory per-room buffer with {}s cooldown, max {} retained.",
                    cooldown.toSeconds(), MAX_RECENT_PER_ROOM);
        }
    }


    /**
     * Broadcast a surprise event to the room's recent buffer. Returns true when
     * the event was broadcast, false when rate-limited or the flag is off.
     */
    public boolean maybeBroadcast(SurpriseEvent event) {
        if (!enabled || event == null || event.getRoomId() == null) return false;
        Instant now = Instant.now();
        Instant last = lastBroadcastByRoom.get(event.getRoomId());
        if (last != null && now.isBefore(last.plus(cooldown))) {
            log.debug("surprise broadcast suppressed roomId={} cooldown active", event.getRoomId());
            return false;
        }
        lastBroadcastByRoom.put(event.getRoomId(), now);
        Deque<SurpriseEvent> buf = recentByRoom.computeIfAbsent(
                event.getRoomId(), k -> new ArrayDeque<>());
        synchronized (buf) {
            buf.addFirst(event);
            while (buf.size() > MAX_RECENT_PER_ROOM) {
                buf.removeLast();
            }
        }
        log.info("surprise broadcast roomId={} paperId={} score={}",
                event.getRoomId(), event.getPaperId(),
                String.format("%.2f", event.getSurpriseScore()));
        return true;
    }

    /**
     * Read-side surface for prompt builders and analytics. Returns the most
     * recent broadcasts in the room (newest first), excluding any that
     * originated from {@code excludeBotId}. Empty when the flag is off, the
     * room has no broadcasts yet, or every recent event was from the excluded
     * bot.
     */
    public List<SurpriseEvent> recentBroadcasts(UUID roomId, UUID excludeBotId, int limit) {
        if (!enabled || roomId == null || limit <= 0) return List.of();
        Deque<SurpriseEvent> buf = recentByRoom.get(roomId);
        if (buf == null) return List.of();
        List<SurpriseEvent> snapshot;
        synchronized (buf) {
            snapshot = new ArrayList<>(buf);
        }
        List<SurpriseEvent> result = new ArrayList<>();
        for (SurpriseEvent e : snapshot) {
            if (excludeBotId != null && excludeBotId.equals(e.getBotId())) continue;
            result.add(e);
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** For tests + observability. Returns the number of broadcasts retained for the room. */
    public int recentBroadcastCount(UUID roomId) {
        if (!enabled || roomId == null) return 0;
        Deque<SurpriseEvent> buf = recentByRoom.get(roomId);
        return buf == null ? 0 : buf.size();
    }
}
