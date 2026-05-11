package com.emergentterrain.repo;

import com.emergentterrain.research.Grant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GrantRepository extends JpaRepository<Grant, UUID> {

    List<Grant> findByRoomIdOrderByGameTickDesc(UUID roomId);

    List<Grant> findByUniversityIdOrderByGameTickDesc(UUID universityId);
}
