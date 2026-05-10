package com.emergentterrain.repo;

import com.emergentterrain.research.Journal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalRepository extends JpaRepository<Journal, UUID> {

    List<Journal> findByRoomId(UUID roomId);

    Optional<Journal> findFirstByRoomIdAndFocusDomain(UUID roomId, String focusDomain);
}
