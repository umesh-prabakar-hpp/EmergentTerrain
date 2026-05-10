package com.emergentterrain.repo;

import com.emergentterrain.research.FundingAgency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FundingAgencyRepository extends JpaRepository<FundingAgency, UUID> {

    List<FundingAgency> findByRoomId(UUID roomId);

    Optional<FundingAgency> findFirstByRoomId(UUID roomId);
}
