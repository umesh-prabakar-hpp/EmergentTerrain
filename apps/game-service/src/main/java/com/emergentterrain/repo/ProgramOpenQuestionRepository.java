package com.emergentterrain.repo;

import com.emergentterrain.research.ProgramOpenQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProgramOpenQuestionRepository extends JpaRepository<ProgramOpenQuestion, UUID> {

    List<ProgramOpenQuestion> findByProgramIdAndStatus(UUID programId,
                                                       ProgramOpenQuestion.Status status);

    List<ProgramOpenQuestion> findByProgramId(UUID programId);
}
