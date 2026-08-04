package com.emergentterrain.repo;

import com.emergentterrain.domain.Profile;
import com.emergentterrain.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {
    List<Profile> findByUser(User user);
    Optional<Profile> findTopByUser_RoleNotOrderByCreatedAtAsc(String role);
    Optional<Profile> findTopByUserOrderByCreatedAtAsc(User user);
}
