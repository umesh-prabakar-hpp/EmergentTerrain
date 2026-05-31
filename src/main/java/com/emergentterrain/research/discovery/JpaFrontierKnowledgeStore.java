package com.emergentterrain.research.discovery;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Phase 30 fork 2b — DB-backed {@link FrontierKnowledgeStore} (survives restart).
 * Thin adapter over Spring Data repositories ({@link FrontierRecordRepository},
 * {@link FrontierConvergenceRepository}, {@link VerifiedMethodRepository}); the
 * keep-the-best + convergence logic lives in {@link FrontierKnowledgeService}.
 */
@Component
public class JpaFrontierKnowledgeStore implements FrontierKnowledgeStore {

    private final FrontierRecordRepository records;
    private final FrontierConvergenceRepository convergence;
    private final VerifiedMethodRepository methods;

    public JpaFrontierKnowledgeStore(FrontierRecordRepository records, FrontierConvergenceRepository convergence,
                                     VerifiedMethodRepository methods) {
        this.records = records;
        this.convergence = convergence;
        this.methods = methods;
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

    @Override
    public Optional<VerifiedMethod> findMethod(String bound, String methodSha256) {
        return methods.findByBoundAndMethodSha256(bound, methodSha256).map(VerifiedMethodEntity::toDto);
    }

    @Override
    public void putMethod(VerifiedMethod method) {
        VerifiedMethodEntity e = methods.findByBoundAndMethodSha256(method.bound(), method.methodSha256())
                .orElseGet(VerifiedMethodEntity::new);
        e.apply(method);
        methods.save(e);
    }

    @Override
    public java.util.List<FrontierRecord> recordsFor(String bound) {
        return records.findByBoundOrderByScaleXAsc(bound).stream().map(FrontierRecordEntity::toDto).toList();
    }

    @Override
    public Optional<VerifiedMethod> bestMethod(String bound) {
        return methods.findByBoundOrderByMaxScaleDescCostMsAsc(bound).stream().findFirst().map(VerifiedMethodEntity::toDto);
    }
}
