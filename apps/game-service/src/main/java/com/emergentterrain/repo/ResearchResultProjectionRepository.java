package com.emergentterrain.repo;

import com.emergentterrain.domain.ResearchResultProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ResearchResultProjectionRepository extends JpaRepository<ResearchResultProjection, UUID> { }
