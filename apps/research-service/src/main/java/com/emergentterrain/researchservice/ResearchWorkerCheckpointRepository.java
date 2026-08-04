package com.emergentterrain.researchservice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ResearchWorkerCheckpointRepository extends JpaRepository<ResearchWorkerCheckpoint, UUID> {
}
