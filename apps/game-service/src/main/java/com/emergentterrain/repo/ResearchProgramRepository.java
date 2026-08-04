package com.emergentterrain.repo;

import com.emergentterrain.research.ResearchProgram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResearchProgramRepository extends JpaRepository<ResearchProgram, UUID> {

    List<ResearchProgram> findByOwnerBotIdAndStatus(UUID ownerBotId, ResearchProgram.Status status);

    List<ResearchProgram> findByRoomId(UUID roomId);

    /** Find the active program a bot is running in a given domain, if any. */
    Optional<ResearchProgram> findFirstByOwnerBotIdAndDomainAndStatus(
            UUID ownerBotId, String domain, ResearchProgram.Status status);
}
