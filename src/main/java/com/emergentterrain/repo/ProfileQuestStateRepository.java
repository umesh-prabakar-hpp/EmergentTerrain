package com.emergentterrain.repo;

import com.emergentterrain.domain.Profile;
import com.emergentterrain.domain.ProfileQuestState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ProfileQuestStateRepository extends JpaRepository<ProfileQuestState, UUID> {
    Optional<ProfileQuestState> findByProfile(Profile profile);
}
