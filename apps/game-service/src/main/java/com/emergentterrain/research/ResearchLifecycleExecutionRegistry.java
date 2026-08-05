package com.emergentterrain.research;

import com.emergentterrain.researchcontract.ResearchLifecycleExecution;
import com.emergentterrain.researchcontract.ResearchLifecycleExecutionWindow;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes the outcome of the legacy lifecycle gate for the game-owned context endpoint.
 * Entries are in-memory observations only; game state remains the durable authority during shadowing.
 */
@Service
public class ResearchLifecycleExecutionRegistry {

    private static final int MAX_EVENTS_PER_ROOM = 256;

    private final ConcurrentHashMap<UUID, RoomJournal> journals = new ConcurrentHashMap<>();

    public void skipped(UUID roomId, long gameTick) {
        journal(roomId).append(gameTick, false, null);
    }

    public void advanced(UUID roomId, long gameTick, long adultToElder) {
        journal(roomId).append(gameTick, true, adultToElder);
    }

    /** Only the observation for the snapshot's exact game tick is safe for a remote projector. */
    public Optional<ResearchLifecycleExecution> forGameTick(UUID roomId, long gameTick) {
        return windowForGameTick(roomId, gameTick).map(window -> window.events().getLast());
    }

    /** Returns a contiguous bounded journal only when its newest event belongs to this snapshot tick. */
    public Optional<ResearchLifecycleExecutionWindow> windowForGameTick(UUID roomId, long gameTick) {
        RoomJournal journal = journals.get(roomId);
        return journal == null ? Optional.empty() : journal.windowForGameTick(gameTick);
    }

    private RoomJournal journal(UUID roomId) {
        return journals.computeIfAbsent(roomId, ignored -> new RoomJournal());
    }

    private static final class RoomJournal {
        private final String epoch = UUID.randomUUID().toString();
        private final Deque<ResearchLifecycleExecution> events = new ArrayDeque<>();
        private long nextSequence = 1L;

        synchronized void append(long gameTick, boolean advanced, Long adultToElder) {
            events.addLast(new ResearchLifecycleExecution(nextSequence++, gameTick, advanced, adultToElder));
            while (events.size() > MAX_EVENTS_PER_ROOM) {
                events.removeFirst();
            }
        }

        synchronized Optional<ResearchLifecycleExecutionWindow> windowForGameTick(long gameTick) {
            ResearchLifecycleExecution latest = events.peekLast();
            if (latest == null || latest.gameTick() != gameTick) {
                return Optional.empty();
            }
            ResearchLifecycleExecution first = events.peekFirst();
            return Optional.of(new ResearchLifecycleExecutionWindow(epoch, first.sequence(), latest.sequence(),
                    List.copyOf(events)));
        }
    }
}
