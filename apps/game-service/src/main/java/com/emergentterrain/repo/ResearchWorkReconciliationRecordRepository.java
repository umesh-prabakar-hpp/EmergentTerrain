package com.emergentterrain.repo;

import com.emergentterrain.domain.ResearchWorkReconciliationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ResearchWorkReconciliationRecordRepository
        extends JpaRepository<ResearchWorkReconciliationRecord, UUID> {
}
