package com.emergentterrain.repo;

import com.emergentterrain.research.question.VerifiedResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Phase 33 lane C (C1) — persistence for the verified-results ledger. */
public interface VerifiedResultRepository extends JpaRepository<VerifiedResult, UUID> {

    List<VerifiedResult> findTop6ByFamilyOrderByCreatedAtDesc(String family);

    /** C2 — the synthesis fit wants the family's full recent inventory. */
    List<VerifiedResult> findTop20ByFamilyOrderByCreatedAtDesc(String family);
}
