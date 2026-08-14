package com.emergentterrain.researchservice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ResearchComputationParityRepository extends JpaRepository<ResearchComputationParityRecord, UUID> { }
