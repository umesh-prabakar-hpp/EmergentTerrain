package com.emergentterrain.researchservice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ResearchBotStateRepository extends JpaRepository<ResearchBotState, UUID> {

    List<ResearchBotState> findByRoomId(UUID roomId);
}
