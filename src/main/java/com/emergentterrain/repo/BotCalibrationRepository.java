package com.emergentterrain.repo;

import com.emergentterrain.research.BotCalibration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BotCalibrationRepository extends JpaRepository<BotCalibration, UUID> {

    Optional<BotCalibration> findByBotId(UUID botId);
}
