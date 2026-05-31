package com.emergentterrain.research.discovery;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 30 fork 2b — DB-backed {@link FrontierKnowledgeStore} (survives restart).
 * Thin adapter over Spring Data repositories; the keep-the-best + convergence logic
 * lives in {@link FrontierKnowledgeService}.
 */
@Component
public class JpaFrontierKnowledgeStore implements FrontierKnowledgeStore {

    interface FrontierRecordRepository extends JpaRepository<FrontierRecordEntity, UUID> {
        Optional<FrontierRecordEntity> findByBoundAndScaleX(String bound, long scaleX);
        @Query("select max(r.scaleX) from FrontierRecordEntity r where r.bound = ?1")
        Long maxScaleX(String bound);
    }

    interface FrontierConvergenceRepository extends JpaRepository<FrontierConvergenceEntity, String> {}

    private final FrontierRecordRepository records;
    private final FrontierConvergenceRepository convergence;

    public JpaFrontierKnowledgeStore(FrontierRecordRepository records, FrontierConvergenceRepository convergence) {
        this.records = records;
        this.convergence = convergence;
    }

    @Override
    public Optional<FrontierRecord> find(String bound, long x) {
        return records.findByBoundAndScaleX(bound, x).map(FrontierRecordEntity::toDto);
    }

    @Override
    public void put(FrontierRecord record) {
        FrontierRecordEntity e = records.findByBoundAndScaleX(record.bound(), record.x())
                .orElseGet(FrontierRecordEntity::new);
        e.apply(record);
        records.save(e);
    }

    @Override
    public long frontierOf(String bound) {
        Long m = records.maxScaleX(bound);
        return m == null ? 0 : m;
    }

    @Override
    public ConvergenceState convergence(String bound) {
        return convergence.findById(bound).map(FrontierConvergenceEntity::toState).orElseGet(ConvergenceState::initial);
    }

    @Override
    public void saveConvergence(String bound, ConvergenceState state) {
        FrontierConvergenceEntity e = convergence.findById(bound).orElseGet(() -> new FrontierConvergenceEntity(bound));
        e.apply(state);
        convergence.save(e);
    }
}
