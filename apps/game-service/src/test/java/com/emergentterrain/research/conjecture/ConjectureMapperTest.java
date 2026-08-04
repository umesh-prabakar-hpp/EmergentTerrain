package com.emergentterrain.research.conjecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Round-trip parity for the engine ↔ entity boundary. Builds an
 * {@link Inequality} with non-trivial state in every field (collections
 * populated, slack-by-config populated, witnesses populated), runs it
 * through {@link ConjectureMapper#toEntity} → {@code toInequality}, and
 * asserts byte-equality of every visible field.
 *
 * <p>Catches: forgotten field in the mapper, JSON encoding asymmetry,
 * default-value bugs.
 */
class ConjectureMapperTest {

    private final ConjectureMapper mapper = new ConjectureMapper(new ObjectMapper());

    @Test
    void roundTripsAllFields() {
        Inequality original = new Inequality("abundant_density", "prime_density", 2.0, 0.0940);
        original.confirmedRuns = 7;
        original.p99Slack = -0.0042;
        original.confirmedConfigs.add("W500");
        original.confirmedConfigs.add("W5k");
        original.confirmedConfigs.add("W50k");
        original.slackByConfig.put("W500", -0.0125);
        original.slackByConfig.put("W5k",  -0.0042);
        original.slackByConfig.put("W50k", -0.0070);
        original.refutedBy.add(new Witness("W500_low", 5000L, 0.0541));

        Conjecture entity = mapper.toEntity(original, "math");

        // Pre-persist sanity
        assertEquals("math", entity.domain());
        assertNotNull(entity.createdAt());
        assertNull(entity.promotedAt(), "fresh conjecture below promotion threshold should not be promoted");

        Inequality restored = mapper.toInequality(entity);

        assertEquals(original.lhs, restored.lhs);
        assertEquals(original.rhs, restored.rhs);
        assertEquals(original.k, restored.k);
        assertEquals(original.c, restored.c);
        assertEquals(original.confirmedRuns, restored.confirmedRuns);
        assertEquals(original.p99Slack, restored.p99Slack);
        assertEquals(original.confirmedConfigs, restored.confirmedConfigs);
        assertEquals(original.slackByConfig, restored.slackByConfig);
        assertEquals(original.refutedBy, restored.refutedBy);
        assertNull(restored.droppedByKnob);
    }

    @Test
    void appliesUpdatesAndPromotes() {
        Inequality original = new Inequality("happy_density", "prime_density", 0.0, 0.156);
        Conjecture entity = mapper.toEntity(original, "math");
        assertNull(entity.promotedAt());

        // Simulate enough confirmations to cross the promotion threshold.
        original.confirmedRuns = ConjectureEngine.PROMOTE_RUNS;
        for (int i = 0; i < ConjectureEngine.PROMOTE_CONFIGS; i++) {
            original.confirmedConfigs.add("cfg-" + i);
        }
        original.c = 0.154;  // tightened

        mapper.apply(original, entity);

        assertEquals(0.154, entity.c());
        assertEquals(ConjectureEngine.PROMOTE_RUNS, entity.confirmedRuns());
        assertNotNull(entity.updatedAt());
        assertNotNull(entity.promotedAt(), "should be promoted after crossing both thresholds");
    }

    @Test
    void handlesEmptyEntityGracefully() {
        // Reconstruct an Inequality from a barely-initialized entity
        // (no collection JSON, no slack — simulates a brand-new row before
        // the engine has run any updates against it).
        Conjecture entity = new Conjecture("math", "lhs_scalar", "rhs_scalar", 1.0, 0.5);

        Inequality restored = mapper.toInequality(entity);

        assertEquals("lhs_scalar", restored.lhs);
        assertEquals(0, restored.confirmedRuns);
        assertEquals(0, restored.confirmedConfigs.size());
        assertEquals(0, restored.slackByConfig.size());
        assertEquals(0, restored.refutedBy.size());
    }
}
