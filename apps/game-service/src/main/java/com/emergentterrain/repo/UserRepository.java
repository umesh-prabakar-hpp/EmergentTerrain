package com.emergentterrain.repo;

import com.emergentterrain.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findTopByRoleNotOrderByCreatedAtAsc(String role);
    long countByRoleNot(String role);
}
