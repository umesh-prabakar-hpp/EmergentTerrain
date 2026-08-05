package com.emergentterrain.researchservice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ResearchLifecycleProjectionRepository extends JpaRepository<ResearchLifecycleProjection, UUID> {
    List<ResearchLifecycleProjection> findByRoomId(UUID roomId);
}
