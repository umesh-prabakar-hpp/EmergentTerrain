package com.emergentterrain.research.question;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 2026-07-06 — two-phase question answering (reserve at serve, confirm at publish). */
class TwoPhaseAnsweringTest {

    @Test
    void reserveConfirmAndSweepLifecycle() {
        PaperOpenQuestion q = new PaperOpenQuestion("P-src", "max_prime_gap", "q?", "RATE");
        assertThat(q.answeredByPaperId()).isNull();

        q.reserve("P-draft");
        assertThat(q.answeredByPaperId()).isEqualTo("P-draft");
        assertThat(q.answeredAt()).isNull();          // reserved, NOT answered

        q.confirmAnswered(Instant.now());
        assertThat(q.answeredAt()).isNotNull();       // published → answered

        PaperOpenQuestion dead = new PaperOpenQuestion("P-src2", "max_prime_gap", "q2?", "RATE");
        dead.reserve("P-dead-draft");
        dead.clearReservation();                       // the run-start sweep path
        assertThat(dead.answeredByPaperId()).isNull();
        assertThat(dead.answeredAt()).isNull();        // servable again
    }
}
