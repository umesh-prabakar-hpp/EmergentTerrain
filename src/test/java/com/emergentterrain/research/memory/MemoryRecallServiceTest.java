package com.emergentterrain.research.memory;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 31 §31.3 — recall block construction: ranking, hard budget, the
 * flag-off / empty-store byte-identical contract, and source pointers.
 */
class MemoryRecallServiceTest {

    private static ResearchMemoryEntity lesson(String content, String topic,
            int strength, int reinforcements, Instant createdAt) throws Exception {
        ResearchMemoryEntity e = new ResearchMemoryEntity(
                ResearchMemoryEntity.TYPE_LESSON, content, "h" + content.hashCode(),
                "ref:" + topic, topic, "MATHEMATICS", null, null, "run");
        set(e, "id", UUID.randomUUID());
        set(e, "createdAt", createdAt);
        set(e, "strength", strength);
        set(e, "reinforcements", reinforcements);
        return e;
    }

    private static void set(Object o, String field, Object v) throws Exception {
        Field f = ResearchMemoryEntity.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, v);
    }

    private static MemoryRecallService svc(ResearchMemoryRepository repo,
            boolean enabled, int topK, int budget) {
        return new MemoryRecallService(repo, enabled, topK, budget);
    }

    @Test
    void rankingPrefersRecentReinforcedStrongLessons() throws Exception {
        ResearchMemoryRepository repo = mock(ResearchMemoryRepository.class);
        Instant now = Instant.now();
        ResearchMemoryEntity old = lesson("OLD weak lesson", "conjecture/cramer_gap_ratio",
                3, 0, now.minusSeconds(90L * 86400));      // 90 days, weak, no reinforcement
        ResearchMemoryEntity hot = lesson("HOT reinforced lesson", "conjecture/cramer_gap_ratio",
                8, 5, now.minusSeconds(2L * 86400));        // recent, strong, reinforced
        when(repo.findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtDesc(
                eq(ResearchMemoryEntity.TYPE_LESSON), anyString()))
                .thenReturn(List.of(old, hot));

        String block = svc(repo, true, 5, 600).recallBlock("conjecture/cramer_gap_ratio");
        assertThat(block).contains("## Prior lessons (compiled");
        // HOT must rank above OLD (appear first).
        assertThat(block.indexOf("HOT")).isLessThan(block.indexOf("OLD"));
        assertThat(block).contains("[ref:conjecture/cramer_gap_ratio]");   // source pointer
    }

    @Test
    void hardBudgetTrimsButAlwaysKeepsOne() throws Exception {
        ResearchMemoryRepository repo = mock(ResearchMemoryRepository.class);
        Instant now = Instant.now();
        String longContent = "x".repeat(500);
        when(repo.findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(List.of(
                        lesson(longContent + "A", "t", 5, 0, now),
                        lesson(longContent + "B", "t", 5, 0, now.minusSeconds(60))));
        // Budget 600 < two 500-char lines; exactly one kept.
        String block = svc(repo, true, 5, 600).recallBlock("t");
        long bullets = block.lines().filter(l -> l.startsWith("- ")).count();
        assertThat(bullets).isEqualTo(1);
    }

    @Test
    void flagOffAndEmptyStore_returnEmpty() {
        ResearchMemoryRepository repo = mock(ResearchMemoryRepository.class);
        // Flag off → never queries.
        assertThat(svc(repo, false, 5, 600).recallBlock("t")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(repo);
        // Flag on, empty pool → empty block (byte-identical to off).
        when(repo.findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtDesc(anyString(), anyString()))
                .thenReturn(List.of());
        assertThat(svc(repo, true, 5, 600).recallBlock("t")).isEmpty();
        assertThat(svc(repo, true, 5, 600).recallBlock(null)).isEmpty();
    }

    @Test
    void repoFailureDegradesToEmpty() {
        ResearchMemoryRepository repo = mock(ResearchMemoryRepository.class);
        when(repo.findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtDesc(anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        assertThat(svc(repo, true, 5, 600).recallBlock("t")).isEmpty();
    }
}
