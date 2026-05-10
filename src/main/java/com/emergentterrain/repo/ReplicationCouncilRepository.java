package com.emergentterrain.repo;

import com.emergentterrain.research.ReplicationCouncil;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplicationCouncilRepository extends JpaRepository<ReplicationCouncil, UUID> {

    List<ReplicationCouncil> findByRoomId(UUID roomId);

    Optional<ReplicationCouncil> findFirstByRoomId(UUID roomId);
}
