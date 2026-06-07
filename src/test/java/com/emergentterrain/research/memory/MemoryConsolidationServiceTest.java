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
