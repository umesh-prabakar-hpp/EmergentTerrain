package com.emergentterrain.repo;

import com.emergentterrain.research.MarketStake;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MarketStakeRepository extends JpaRepository<MarketStake, UUID> {

    List<MarketStake> findByMarketId(UUID marketId);

    List<MarketStake> findByBotId(UUID botId);

    /** Phase 4b — used by auto-staking to skip bots that already staked on this market. */
    boolean existsByMarketIdAndBotId(UUID marketId, UUID botId);
}
