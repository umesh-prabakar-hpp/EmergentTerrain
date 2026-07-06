package com.emergentterrain.repo;

import com.emergentterrain.research.question.PaperOpenQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Phase 33 lane T (T1) — persistence for the open-question ledger. */
public interface PaperOpenQuestionRepository extends JpaRepository<PaperOpenQuestion, UUID> {

    List<PaperOpenQuestion> findByFamilyAndAnsweredAtIsNull(String family);

    /** Two-phase answering (2026-07-06): servable = never reserved. */
    List<PaperOpenQuestion> findByFamilyAndAnsweredByPaperIdIsNull(String family);

    /** Publish-side confirmation lookup. */
    List<PaperOpenQuestion> findByAnsweredByPaperIdAndAnsweredAtIsNull(String paperId);

    /** Run-start sweep: reservations never confirmed (draft died) → back to servable. */
    List<PaperOpenQuestion> findByAnsweredByPaperIdIsNotNullAndAnsweredAtIsNull();

    List<PaperOpenQuestion> findByAnsweredAtIsNull();

    long countByPaperId(String paperId);
}
