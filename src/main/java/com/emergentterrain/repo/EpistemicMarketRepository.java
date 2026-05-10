package com.emergentterrain.repo;

import com.emergentterrain.research.EpistemicMarket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EpistemicMarketRepository extends JpaRepository<EpistemicMarket, UUID> {

    List<EpistemicMarket> findByRoomIdAndStatus(UUID roomId, EpistemicMarket.Status status);

    List<EpistemicMarket> findByRoomIdAndDomainAndStatus(
            UUID roomId, String domain, EpistemicMarket.Status status);

    /** Used to detect existing markets when a paper publishes (avoids duplicates). */
    Optional<EpistemicMarket> findFirstByHypothesisPaperId(String hypothesisPaperId);
}
