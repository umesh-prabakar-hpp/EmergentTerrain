package com.emergentterrain.repo;

import com.emergentterrain.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {
    Optional<Room> findTopByNameOrderByCreatedAtAsc(String name);
    Optional<Room> findTopByOrderByCreatedAtAsc();

    /**
     * Phase 3 Iter 6 (sandbox flag) — same as
     * {@link #findTopByOrderByCreatedAtAsc()} but skips sandbox rooms so
     * the "default room" picker never lands on a research-only sandbox.
     * Bots in sandbox rooms still file intervention bills as before; the
     * isolation is at the player-visibility / default-routing layer.
     */
    Optional<Room> findTopBySandboxFalseOrderByCreatedAtAsc();
}
