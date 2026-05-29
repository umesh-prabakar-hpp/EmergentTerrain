package com.emergentterrain.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-05-29 — pins {@link ReworkDraftSnapshot}, the serialization-safe draft
 * snapshot persisted on INCOMPLETE papers for the re-queue. The two things that
 * must hold: it JSON round-trips through Jackson (incl. the nested
 * {@link PreRegistration} record, enums, and UUIDs), and rehydrating preserves
 * the fields the re-narration needs — above all the {@code paperId} (so the
 * re-publish upserts the same row, not a duplicate). Pure → no Mockito.
 */
class ReworkDraftSnapshotTest {

    private static PaperDraft sampleDraft() {
        return new PaperDraft(
                UUID.randomUUID(), "bot-1", "PHYSICS", "Stefan-Boltzmann",
                "A Title", "raw findings text",
                "Hypothesis near 5800 K.", "Conclusion.", "COMPUTATIONAL",
                List.of("rev-a", "rev-b"),
                0.7, 0.6, 0.0, 1234L, Instant.now(), 3, "P-a328-998",
                42, 3.5, false, 0.8, "THEORETICAL",
                List.of(),                 // recentPapers — dropped by the snapshot
                "research hypothesis",
                new PreRegistration("h", "pred", "falsify", "mapping"),
                ResultClassification.SUPPORTED,
                UUID.randomUUID());        // plannerQuestionId — dropped by the snapshot
    }

    @Test
    void roundTripsThroughJackson() throws Exception {
        // A plain ObjectMapper (no JavaTime module) must suffice — the snapshot
        // intentionally carries no Instant. Records + enums + UUID + nested
        // PreRegistration record must all survive.
        ObjectMapper mapper = new ObjectMapper();
        ReworkDraftSnapshot snap = ReworkDraftSnapshot.from(sampleDraft());

        String json = mapper.writeValueAsString(snap);
        ReworkDraftSnapshot back = mapper.readValue(json, ReworkDraftSnapshot.class);

        assertThat(back).isEqualTo(snap);                       // records → value equality
        assertThat(back.preRegistration().prediction()).isEqualTo("pred");
        assertThat(back.resultClassification()).isEqualTo(ResultClassification.SUPPORTED);
        assertThat(back.kind()).isEqualTo(PaperKind.DOMAIN);
    }

    @Test
    void fromThenToDraftPreservesReNarrationInputs() {
        PaperDraft original = sampleDraft();
        PaperDraft rehydrated = ReworkDraftSnapshot.from(original).toDraft();

        // The fields the narration pipeline actually consumes are preserved...
        assertThat(rehydrated.paperId()).isEqualTo("P-a328-998");   // SAME id → in-place upsert
        assertThat(rehydrated.roomId()).isEqualTo(original.roomId());
        assertThat(rehydrated.domain()).isEqualTo("PHYSICS");
        assertThat(rehydrated.topicName()).isEqualTo("Stefan-Boltzmann");
        assertThat(rehydrated.rawFindings()).isEqualTo("raw findings text");
        assertThat(rehydrated.param1()).isEqualTo(42);
        assertThat(rehydrated.param2()).isEqualTo(3.5);
        assertThat(rehydrated.generation()).isEqualTo(3);
        assertThat(rehydrated.preRegistration()).isEqualTo(original.preRegistration());
        assertThat(rehydrated.resultClassification()).isEqualTo(ResultClassification.SUPPORTED);

        // ...and the dropped bookkeeping fields default safely (no entity refs,
        // no stale planner linkage carried into the rework).
        assertThat(rehydrated.recentPapers()).isEmpty();
        assertThat(rehydrated.plannerQuestionId()).isNull();
        assertThat(rehydrated.isFollowUp()).isFalse();
    }

    @Test
    void fullRoundTripJsonToDraftKeepsPaperId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(ReworkDraftSnapshot.from(sampleDraft()));
        PaperDraft draft = mapper.readValue(json, ReworkDraftSnapshot.class).toDraft();
        assertThat(draft.paperId()).isEqualTo("P-a328-998");
        assertThat(draft.param1()).isEqualTo(42);
    }
}
