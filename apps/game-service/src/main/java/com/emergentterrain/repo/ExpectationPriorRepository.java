package com.emergentterrain.repo;

import com.emergentterrain.research.ExpectationPrior;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExpectationPriorRepository extends JpaRepository<ExpectationPrior, UUID> {

    Optional<ExpectationPrior> findByBotIdAndDomain(UUID botId, String domain);
}
