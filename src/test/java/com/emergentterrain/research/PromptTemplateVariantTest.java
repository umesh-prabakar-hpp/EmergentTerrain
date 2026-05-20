package com.emergentterrain.research;

import com.emergentterrain.repo.PromptTemplateVariantRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 4c Piece 3 PR1 — pins the {@link PromptTemplateVariant} entity
 * shape + the idempotent-upsert behaviour of
 * {@link PromptTemplateRegistry#syncVariantsToDatabase()}.
 *
 * <p>The test goal is acceptance for PR1 (per the piece-3 design doc):
 * "existing tests pass; mvn test … the table exists; two startups of
 * the same registry don't duplicate rows."
 */
class PromptTemplateVariantTest {

    // ────────────── Entity shape ──────────────

    @Test
    void constructor_setsRequiredFields_withDefaultStatusAndZeroCounters() {
        Instant t = Instant.parse("2026-05-20T00:00:00Z");
        PromptTemplateVariant v = new PromptTemplateVariant(
                "narration.primary", "v1", "body", t);
        assertThat(v.getTemplateId()).isEqualTo("narration.primary");
        assertThat(v.getVersionTag()).isEqualTo("v1");
        assertThat(v.getBody()).isEqualTo("body");
        assertThat(v.getStatus()).isEqualTo(PromptTemplateVariant.Status.ACTIVE);
        assertThat(v.getCreatedAt()).isEqualTo(t);
        assertThat(v.getPapersNarrated()).isZero();
        assertThat(v.getReplicationsConfirmed()).isZero();
        assertThat(v.getReplicationsFailed()).isZero();
        assertThat(v.getRolledBackAt()).isNull();
    }

    @Test
    void setStatus_rolledBack_stampsRolledBackAt() {
        PromptTemplateVariant v = new PromptTemplateVariant(
                "x", "v1", "b", Instant.parse("2026-05-20T00:00:00Z"));
        assertThat(v.getRolledBackAt()).isNull();
        v.setStatus(PromptTemplateVariant.Status.ROLLED_BACK);
        assertThat(v.getStatus()).isEqualTo(PromptTemplateVariant.Status.ROLLED_BACK);
        assertThat(v.getRolledBackAt()).isNotNull();
    }

    @Test
    void setStatus_retired_alsoStampsRolledBackAt() {
        // Differentiation between ROLLED_BACK and RETIRED is in the status enum
        // value, not in whether the timestamp is set.
        PromptTemplateVariant v = new PromptTemplateVariant(
                "x", "v1", "b", Instant.parse("2026-05-20T00:00:00Z"));
        v.setStatus(PromptTemplateVariant.Status.RETIRED);
        assertThat(v.getStatus()).isEqualTo(PromptTemplateVariant.Status.RETIRED);
        assertThat(v.getRolledBackAt()).isNotNull();
    }

    @Test
    void setStatus_backToActive_doesNotClearRolledBackAt() {
        // The timestamp is "the moment it stopped being active"; revisiting
        // ACTIVE is rare (operator-driven) and the original timestamp is
        // forensically useful.
        PromptTemplateVariant v = new PromptTemplateVariant(
                "x", "v1", "b", Instant.parse("2026-05-20T00:00:00Z"));
        v.setStatus(PromptTemplateVariant.Status.ROLLED_BACK);
        Instant stamp = v.getRolledBackAt();
        assertThat(stamp).isNotNull();
        v.setStatus(PromptTemplateVariant.Status.ACTIVE);
        assertThat(v.getRolledBackAt()).isEqualTo(stamp);
    }

    @Test
    void counter_increments() {
        PromptTemplateVariant v = new PromptTemplateVariant(
                "x", "v1", "b", Instant.parse("2026-05-20T00:00:00Z"));
        v.incrementPapersNarrated();
        v.incrementPapersNarrated();
        v.incrementReplicationsConfirmed();
        v.incrementReplicationsFailed();
        v.incrementReplicationsFailed();
        v.incrementReplicationsFailed();
        assertThat(v.getPapersNarrated()).isEqualTo(2);
        assertThat(v.getReplicationsConfirmed()).isEqualTo(1);
        assertThat(v.getReplicationsFailed()).isEqualTo(3);
    }

    // ────────────── Registry → repo idempotent upsert ──────────────

    @Test
    void syncVariantsToDatabase_nullRepo_isSilentNoop() {
        // The unit-test path that constructs the registry directly
        // (`new PromptTemplateRegistry()`) leaves variantRepo null.
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        r.register(new PromptTemplate("x", "v1", "body"));
        // Doesn't throw — that's the contract.
        r.syncVariantsToDatabase();
    }

    @Test
    void syncVariantsToDatabase_emptyRepo_insertsAllRegisteredVariants() throws Exception {
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findByTemplateIdAndVersionTag(any(), any())).thenReturn(Optional.empty());

        PromptTemplateRegistry r = registryWith(repo);
        r.register(new PromptTemplate("a", "v1", "body-a"));
        r.register(new PromptTemplate("b", "v1", "body-b"));
        r.register(new PromptTemplate("a", "v2", "body-a-v2"));

        r.syncVariantsToDatabase();

        // Three distinct (id, version) pairs → three inserts.
        verify(repo, times(3)).save(any(PromptTemplateVariant.class));
    }

    @Test
    void syncVariantsToDatabase_existingRow_unchangedBody_doesNothing() throws Exception {
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        PromptTemplateVariant existing = new PromptTemplateVariant(
                "a", "v1", "body-a", Instant.parse("2026-05-19T00:00:00Z"));
        when(repo.findByTemplateIdAndVersionTag("a", "v1")).thenReturn(Optional.of(existing));

        PromptTemplateRegistry r = registryWith(repo);
        r.register(new PromptTemplate("a", "v1", "body-a"));  // same body
        r.syncVariantsToDatabase();

        // Body matches → no save call (the existing row stays untouched,
        // counters preserved across restart).
        verify(repo, never()).save(any(PromptTemplateVariant.class));
    }

    @Test
    void syncVariantsToDatabase_existingRow_changedBody_updatesBodyOnly() throws Exception {
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        PromptTemplateVariant existing = new PromptTemplateVariant(
                "a", "v1", "old-body", Instant.parse("2026-05-19T00:00:00Z"));
        existing.incrementPapersNarrated();
        existing.incrementReplicationsConfirmed();
        when(repo.findByTemplateIdAndVersionTag("a", "v1")).thenReturn(Optional.of(existing));

        PromptTemplateRegistry r = registryWith(repo);
        r.register(new PromptTemplate("a", "v1", "new-body"));
        r.syncVariantsToDatabase();

        // Body diff → save once. Counters preserved on the existing instance.
        assertThat(existing.getBody()).isEqualTo("new-body");
        assertThat(existing.getPapersNarrated()).isEqualTo(1);
        assertThat(existing.getReplicationsConfirmed()).isEqualTo(1);
        verify(repo, times(1)).save(existing);
    }

    @Test
    void syncVariantsToDatabase_idempotentAcrossTwoCalls() throws Exception {
        // The acceptance criterion from the piece-3 doc:
        // "two startups of the same registry don't duplicate rows."
        // Simulated by calling syncVariantsToDatabase() twice; the second
        // call sees the rows from the first and no-ops.
        Map<String, PromptTemplateVariant> store = new HashMap<>();
        PromptTemplateVariantRepository repo = mock(PromptTemplateVariantRepository.class);
        when(repo.findByTemplateIdAndVersionTag(any(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0) + "|" + inv.getArgument(1);
            return Optional.ofNullable(store.get(key));
        });
        when(repo.save(any(PromptTemplateVariant.class))).thenAnswer(inv -> {
            PromptTemplateVariant v = inv.getArgument(0);
            store.put(v.getTemplateId() + "|" + v.getVersionTag(), v);
            return v;
        });

        PromptTemplateRegistry r = registryWith(repo);
        r.register(new PromptTemplate("a", "v1", "body-a"));
        r.register(new PromptTemplate("b", "v1", "body-b"));

        r.syncVariantsToDatabase();  // first startup
        r.syncVariantsToDatabase();  // second startup — should no-op

        // Exactly two rows in the store after two calls.
        assertThat(store).hasSize(2);
        // save was called exactly twice (during the first sync; second
        // call's body-match short-circuit means zero additional saves).
        verify(repo, times(2)).save(any(PromptTemplateVariant.class));
    }

    // ────────────── helpers ──────────────

    /**
     * Builds a {@link PromptTemplateRegistry} with the {@code variantRepo}
     * field injected via reflection. {@code @Autowired(required=false)}
     * field plus a unit-test path that doesn't use Spring DI means
     * reflection is the cleanest way to inject — adding a setter just
     * for tests would broaden the public surface needlessly.
     */
    private static PromptTemplateRegistry registryWith(
            PromptTemplateVariantRepository repo) throws Exception {
        PromptTemplateRegistry r = new PromptTemplateRegistry();
        Field f = PromptTemplateRegistry.class.getDeclaredField("variantRepo");
        f.setAccessible(true);
        f.set(r, repo);
        return r;
    }
}
