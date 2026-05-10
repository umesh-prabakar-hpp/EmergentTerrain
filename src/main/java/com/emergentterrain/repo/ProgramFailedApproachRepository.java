package com.emergentterrain.repo;

import com.emergentterrain.research.ProgramFailedApproach;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProgramFailedApproachRepository extends JpaRepository<ProgramFailedApproach, UUID> {

    List<ProgramFailedApproach> findByProgramId(UUID programId);
}
