package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inequality ↔ Conjecture conversion. The engine works with the
 * mutable in-memory {@link Inequality}; persistence works with
 * {@link Conjecture}. This mapper is the single place where the two
 * representations meet, plus the only place that knows how to encode
 * the JSON-blob fields ({@code confirmedConfigsJson}, etc.).
 *
 * <p>Constructor-injectable so tests can pass a fresh ObjectMapper
 * without spinning up a Spring context.
 */
@Component
public class ConjectureMapper {

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, Double>> STRING_DOUBLE_MAP =
            new TypeReference<>() {};
    private static final TypeReference<List<Witness>> WITNESS_LIST =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ConjectureMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Build a fresh DB row from an in-memory inequality (first time we persist it). */
    public Conjecture toEntity(Inequality ineq, String domain) {
        Conjecture e = new Conjecture(domain, ineq.lhs, ineq.rhs, ineq.k, ineq.c);
        copyMutableFields(ineq, e);
        return e;
    }

    /** Re-sync an existing DB row from a mutated in-memory inequality. */
    public void apply(Inequality ineq, Conjecture entity) {
        entity.setK(ineq.k);
        entity.setC(ineq.c);
        copyMutableFields(ineq, entity);
        entity.setUpdatedAt(Instant.now());
        if (entity.promotedAt() == null
                && ineq.confirmedRuns >= ConjectureEngine.PROMOTE_RUNS
                && ineq.confirmedConfigs.size() >= ConjectureEngine.PROMOTE_CONFIGS) {
            entity.setPromotedAt(Instant.now());
        }
    }

    /** Reconstruct an in-memory inequality from a persisted row. */
    public Inequality toInequality(Conjecture entity) {
        Inequality ineq = new Inequality(entity.lhs(), entity.rhs(), entity.k(), entity.c());
        ineq.confirmedRuns = entity.confirmedRuns();
        ineq.p99Slack = entity.p99Slack();
        ineq.droppedByKnob = entity.droppedByKnob();
        ineq.confirmedConfigs.addAll(parseList(entity.confirmedConfigsJson(), STRING_LIST));
        ineq.slackByConfig.putAll(parseMap(entity.slackByConfigJson(), STRING_DOUBLE_MAP));
        ineq.refutedBy.addAll(parseList(entity.refutedByJson(), WITNESS_LIST));
        return ineq;
    }

    private void copyMutableFields(Inequality ineq, Conjecture entity) {
        // 2026-06-05 — HIGH-WATER MARK, not overwrite. The empty-pool re-seed loop
        // regenerates every retired pair fresh each pass (confirmedRuns 0–1 before its
        // counterexample) and re-saved that low count over the row's history, so the
        // whole corpus decayed to max(confirmed_runs)=1 — silently starving every
        // consumer of held-out evidence: anomaly mining (30.1, "0 surviving" forever),
        // and the 30.5 vocabulary-expansion anomaly-stratum targets (budget 0).
        // confirmedRuns now records the most runs this relation EVER survived.
        entity.setConfirmedRuns(Math.max(entity.confirmedRuns(), ineq.confirmedRuns));
        entity.setP99Slack(ineq.p99Slack);
        entity.setDroppedByKnob(ineq.droppedByKnob);
        entity.setConfirmedConfigsJson(writeJson(ineq.confirmedConfigs));
        entity.setSlackByConfigJson(writeJson(ineq.slackByConfig));
        entity.setRefutedByJson(writeJson(ineq.refutedBy));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Bubble up — every input here is plain JSON-safe data. If we
            // fail, persistence is in an unsafe state and we want a loud
            // stack trace, not a silent empty-string write.
            throw new IllegalStateException("failed to encode conjecture field as JSON", e);
        }
    }

    private <T> List<T> parseList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to decode JSON list from conjecture row", e);
        }
    }

    private <V> Map<String, V> parseMap(String json, TypeReference<Map<String, V>> typeRef) {
        if (json == null || json.isEmpty()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to decode JSON map from conjecture row", e);
        }
    }
}
