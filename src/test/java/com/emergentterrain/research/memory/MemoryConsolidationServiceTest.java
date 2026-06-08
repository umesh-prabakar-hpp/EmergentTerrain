package com.emergentterrain.research.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 31 §31.2 acceptance: confirm-then-refute produces a superseding chain
 * (not two live contradictory entries); per-pass LLM calls ≤ cap; re-running
 * on unchanged input is a no-op.
 */
class MemoryConsolidationServiceTest {

    private final List<ResearchMemoryEntity> store = new ArrayList<>();
    private ResearchMemoryRepository repo;
    private ResearchMemoryService memSvc;

    private ResearchMemoryEntity row(String type, String content, String topic,
            Instant createdAt) throws Exception {
        ResearchMemoryEntity e = new ResearchMemoryEntity(type, content,
                ResearchMemoryService.sha256(type + "\n" + content),
                "test", topic, "MATHEMATICS", null, null, "run-test");
        // Reflection-set id + createdAt (entity is JPA-managed in prod).
        var idF = ResearchMemoryEntity.class.getDeclaredField("id");
        idF.setAccessible(true);
        idF.set(e, UUID.randomUUID());
        var cF = ResearchMemoryEntity.class.getDeclaredField("createdAt");
        cF.setAccessible(true);
        cF.set(e, createdAt);
        store.add(e);
        return e;
    }

    @BeforeEach
    void setUp() {
        store.clear();
        repo = mock(ResearchMemoryRepository.class);
        lenient().when(repo.findByCreatedAtAfterOrderByCreatedAtAsc(any())).thenAnswer(inv -> {
            Instant after = inv.getArgument(0);
            return store.stream().filter(r -> r.createdAt().isAfter(after))
                    .sorted(Comparator.comparing(ResearchMemoryEntity::createdAt)).toList();
        });
        lenient().when(repo.findByTypeAndTopicAndIsLatestTrueOrderByCreatedAtAsc(
                anyString(), anyString())).thenAnswer(inv -> {
            String type = inv.getArgument(0);
            String topic = inv.getArgument(1);
            return store.stream().filter(r -> type.equals(r.type())
                            && topic.equals(r.topic()) && r.isLatest())
                    .sorted(Comparator.comparing(ResearchMemoryEntity::createdAt)).toList();
        });
        lenient().when(repo.findFirstByTypeAndTopicOrderByCreatedAtDesc(
                anyString(), anyString())).thenAnswer(inv -> {
            String type = inv.getArgument(0);
            String topic = inv.getArgument(1);
            return store.stream().filter(r -> type.equals(r.type()) && topic.equals(r.topic()))
                    .max(Comparator.comparing(ResearchMemoryEntity::createdAt));
        });
        // 2026-06-08 backlog-draining lesson path: topics with >=min FACTs and
        // no live LESSON, biggest first.
        lenient().when(repo.findTopicsNeedingLesson(org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
            int min = inv.getArgument(0);
            java.util.Map<String, Long> factCounts = store.stream()
                    .filter(r -> ResearchMemoryEntity.TYPE_FACT.equals(r.type()) && r.topic() != null)
                    .collect(java.util.stream.Collectors.groupingBy(
                            ResearchMemoryEntity::topic, java.util.stream.Collectors.counting()));
            java.util.Set<String> haveLesson = store.stream()
                    .filter(r -> ResearchMemoryEntity.TYPE_LESSON.equals(r.type()) && r.isLatest())
                    .map(ResearchMemoryEntity::topic)
                    .collect(java.util.stream.Collectors.toSet());
            return factCounts.entrySet().stream()
                    .filter(e -> e.getValue() >= min && !haveLesson.contains(e.getKey()))
                    .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(java.util.Map.Entry::getKey).toList();
        });
        lenient().when(repo.findByTypeAndTopicOrderByCreatedAtAsc(anyString(), anyString()))
                .thenAnswer(inv -> {
            String type = inv.getArgument(0);
            String topic = inv.getArgument(1);
            return store.stream().filter(r -> type.equals(r.type()) && topic.equals(r.topic()))
                    .sorted(Comparator.comparing(ResearchMemoryEntity::createdAt)).toList();
        });
        lenient().when(repo.findByContentHash(anyString())).thenAnswer(inv ->
                store.stream().filter(r -> r.contentHash().equals(inv.getArgument(0)))
                        .findFirst());
        lenient().when(repo.save(any(ResearchMemoryEntity.class))).thenAnswer(inv -> {
            ResearchMemoryEntity e = inv.getArgument(0);
            if (store.stream().noneMatch(r -> r == e)) {
                // capture() path: assign id/createdAt like JPA would.
                var idF = ResearchMemoryEntity.class.getDeclaredField("id");
                idF.setAccessible(true);
                if (idF.get(e) == null) idF.set(e, UUID.randomUUID());
                store.add(e);
            }
            return e;
        });
        memSvc = new ResearchMemoryService(repo, true);
    }

    private MemoryConsolidationService service(int cap) {
        MemoryConsolidationService svc = new MemoryConsolidationService(
                repo, memSvc, null, true, cap, 3);
        svc.setSynthesizer(p -> "LESSON: synthesized from " + p.length() + " chars");
        return svc;
    }

    @Test
    void confirmThenRefute_producesSupersedingChain() throws Exception {
        Instant t0 = Instant.now().minusSeconds(600);
        ResearchMemoryEntity confirm = row(ResearchMemoryEntity.TYPE_FACT,
                "paper outcome: P-b72c-214 topic=conjecture/cramer_gap_ratio "
                        + "status=NEEDS_REVISION classification=SUPPORTED peer=0.682 hypothesis=x",
                "conjecture/cramer_gap_ratio", t0);
        ResearchMemoryEntity refute = row(ResearchMemoryEntity.TYPE_FACT,
                "conjecture retired: cramer_gap_ratio <= 0*mertens_per_sqrt_W + 0.4522 "
                        + "— reason=_counterexample_ runs=3",
                "conjecture/cramer_gap_ratio", t0.plusSeconds(120));

        MemoryConsolidationService.PassResult r = service(10).runPass();

        assertThat(r.superseded()).isEqualTo(1);
        assertThat(confirm.isLatest()).isFalse();             // not deleted, not live
        assertThat(refute.supersedesId()).isEqualTo(confirm.id());   // lineage queryable
        assertThat(refute.isLatest()).isTrue();
    }

    @Test
    void refutationNeverSupersedesNEWERConfirm_timestampCausal() throws Exception {
        Instant t0 = Instant.now().minusSeconds(600);
        ResearchMemoryEntity refute = row(ResearchMemoryEntity.TYPE_FACT,
                "conjecture retired: x <= 2*y + 1 — reason=_counterexample_ runs=1",
                "conjecture/x", t0);
        ResearchMemoryEntity newerConfirm = row(ResearchMemoryEntity.TYPE_FACT,
                "paper outcome: P-1 topic=conjecture/x status=PUBLISHED "
                        + "classification=SUPPORTED peer=0.7 hypothesis=h",
                "conjecture/x", t0.plusSeconds(60));

        service(10).runPass();
        assertThat(newerConfirm.isLatest()).isTrue();   // newer belief stands
        assertThat(refute.supersedesId()).isNull();
    }

    @Test
    void groupsBelowMinSizeGetNoLesson_andCapBoundsCalls() throws Exception {
        Instant t0 = Instant.now().minusSeconds(600);
        // Topic A: 3 facts → one lesson. Topic B: 2 facts → none.
        for (int i = 0; i < 3; i++) {
            row(ResearchMemoryEntity.TYPE_FACT, "fact A" + i, "topicA", t0.plusSeconds(i));
        }
        row(ResearchMemoryEntity.TYPE_FACT, "fact B0", "topicB", t0.plusSeconds(10));
        row(ResearchMemoryEntity.TYPE_FACT, "fact B1", "topicB", t0.plusSeconds(11));

        AtomicInteger calls = new AtomicInteger();
        MemoryConsolidationService svc = service(10);
        svc.setSynthesizer(p -> {
            calls.incrementAndGet();
            return "L";
        });
        MemoryConsolidationService.PassResult r = svc.runPass();
        assertThat(r.lessons()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);

        // Cap=0: same input shape, zero calls.
        store.removeIf(e -> ResearchMemoryEntity.TYPE_LESSON.equals(e.type())
                || ResearchMemoryEntity.TYPE_INSIGHT.equals(e.type()));
        calls.set(0);
        MemoryConsolidationService capped = service(0);
        capped.setSynthesizer(p -> {
            calls.incrementAndGet();
            return "L";
        });
        capped.runPass();
        assertThat(calls.get()).isZero();
    }

    @Test
    void deferredGroupsDrainAcrossPasses_evenAfterCursorAdvances() throws Exception {
        // THE bug fix: pass 1 caps at 1 lesson and advances the cursor past
        // ALL facts; the deferred topic must still consolidate next pass
        // (cursor-independent), not be orphaned forever.
        Instant t0 = Instant.now().minusSeconds(600);
        for (int i = 0; i < 3; i++) {
            row(ResearchMemoryEntity.TYPE_FACT, "big topic fact " + i, "conjecture/cramer", t0.plusSeconds(i));
        }
        for (int i = 0; i < 3; i++) {
            row(ResearchMemoryEntity.TYPE_FACT, "other fact " + i, "conjecture/other", t0.plusSeconds(10 + i));
        }
        MemoryConsolidationService svc = service(1);   // cap = 1 lesson/pass

        MemoryConsolidationService.PassResult p1 = svc.runPass();
        assertThat(p1.lessons()).isEqualTo(1);
        // No NEW facts arrive — cursor advanced past everything.
        MemoryConsolidationService.PassResult p2 = svc.runPass();
        assertThat(p2.newRows()).isZero();             // no fresh facts
        assertThat(p2.lessons()).isEqualTo(1);         // but the deferred group drains
        // Both topics now have lessons; a third pass is a true no-op.
        assertThat(svc.runPass().lessons()).isZero();
        assertThat(store.stream()
                .filter(e -> ResearchMemoryEntity.TYPE_LESSON.equals(e.type())).count())
                .isEqualTo(2);
    }

    @Test
    void rerunOnUnchangedInput_isANoOp() throws Exception {
        Instant t0 = Instant.now().minusSeconds(600);
        for (int i = 0; i < 3; i++) {
            row(ResearchMemoryEntity.TYPE_FACT, "fact " + i, "topicA", t0.plusSeconds(i));
        }
        MemoryConsolidationService svc = service(10);
        MemoryConsolidationService.PassResult first = svc.runPass();
        assertThat(first.newRows()).isEqualTo(3);
        // The crystal row written by pass 1 is the cursor for pass 2.
        MemoryConsolidationService.PassResult second = svc.runPass();
        assertThat(second.newRows()).isZero();
        assertThat(second.lessons()).isZero();
    }
}
