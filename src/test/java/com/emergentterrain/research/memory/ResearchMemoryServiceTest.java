package com.emergentterrain.research.memory;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 31 §31.1 — insert-or-reinforce semantics, the replay-safety
 * acceptance bar ("duplicate capture of an identical fact dedups by hash;
 * row count stable on replay"), and the flag-off contract.
 */
class ResearchMemoryServiceTest {

    @Test
    void duplicateCaptureReinforcesInsteadOfInserting() {
        ResearchMemoryRepository repo = mock(ResearchMemoryRepository.class);
        AtomicInteger inserts = new AtomicInteger();
        java.util.Map<String, ResearchMemoryEntity> store = new java.util.HashMap<>();
        when(repo.findByContentHash(anyString())).thenAnswer(
                inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(repo.save(any(ResearchMemoryEntity.class))).thenAnswer(inv -> {
            ResearchMemoryEntity e = inv.getArgument(0);
            if (!store.containsKey(e.contentHash())) inserts.incrementAndGet();
            store.put(e.contentHash(), e);
            return e;
        });
        ResearchMemoryService svc = new ResearchMemoryService(repo, true);

        String content = "conjecture retired: max_prime_gap <= 2*log_window_size + 35.04 "
                + "— reason=_counterexample_ runs=7";
        svc.capture(ResearchMemoryEntity.TYPE_FACT, content, "miner:_counterexample_",
                "conjecture/max_prime_gap", "MATHEMATICS", null, null);
        svc.capture(ResearchMemoryEntity.TYPE_FACT, content, "miner:_counterexample_",
                "conjecture/max_prime_gap", "MATHEMATICS", null, null);
        svc.capture(ResearchMemoryEntity.TYPE_FACT, content, "miner:_counterexample_",
                "conjecture/max_prime_gap", "MATHEMATICS", null, null);

        assertThat(inserts.get()).isEqualTo(1);          // row count stable on replay
        ResearchMemoryEntity row = store.values().iterator().next();
        assertThat(row.reinforcements()).isEqualTo(2);   // two reinforcements
        assertThat(row.isLatest()).isTrue();
    }

    @Test
    void differentTypeSameContent_isADifferentMemory() throws Exception {
        // The hash address includes the type: a FACT and a LESSON with the
        // same text are distinct rows.
        assertThat(ResearchMemoryService.sha256("FACT\nx"))
                .isNotEqualTo(ResearchMemoryService.sha256("LESSON\nx"));
    }

    @Test
    void flagOff_capturesNothing() {
        ResearchMemoryRepository repo = mock(ResearchMemoryRepository.class);
        ResearchMemoryService svc = new ResearchMemoryService(repo, false);
        assertThat(svc.capture(ResearchMemoryEntity.TYPE_FACT, "anything", "ref",
                "t", "MATHEMATICS", UUID.randomUUID(), "bot")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(repo);
    }

    @Test
    void blankContentAndRepoFailures_degradeQuietly() {
        ResearchMemoryRepository repo = mock(ResearchMemoryRepository.class);
        when(repo.findByContentHash(anyString())).thenThrow(new RuntimeException("db down"));
        ResearchMemoryService svc = new ResearchMemoryService(repo, true);
        assertThat(svc.capture(ResearchMemoryEntity.TYPE_FACT, "  ", "r", "t",
                "d", null, null)).isEmpty();
        assertThat(svc.capture(ResearchMemoryEntity.TYPE_FACT, "real content", "r", "t",
                "d", null, null)).isEmpty();   // db failure → empty, no throw
    }
}
