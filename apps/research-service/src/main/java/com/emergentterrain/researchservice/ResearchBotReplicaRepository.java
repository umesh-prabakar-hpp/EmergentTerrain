package com.emergentterrain.researchservice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ResearchBotReplicaRepository extends JpaRepository<ResearchBotReplica, UUID> {

    List<ResearchBotReplica> findByRoomId(UUID roomId);
}
