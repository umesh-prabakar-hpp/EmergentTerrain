package com.emergentterrain.repo;

import com.emergentterrain.domain.GameConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GameConfirmationRepository extends JpaRepository<GameConfirmation, UUID> {
}
