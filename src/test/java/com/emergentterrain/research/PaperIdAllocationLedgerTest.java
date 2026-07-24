package com.emergentterrain.research;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaperIdAllocationLedgerTest {

    @TempDir
    Path state;

    @Test
    void reservationSurvivesRestartWhenTheDraftWasNeverPublished() {
        UUID room = UUID.fromString("b72c4e10-007e-4e09-a75a-72861893ebe7");
        PaperIdAllocationLedger firstJvm = new PaperIdAllocationLedger(state);

        long suppressedDraft = firstJvm.reserve(room, 77627897L);

        // A new JVM sees only the published DB floor (still 77627897), but the allocation log
        // retains the suppressed draft and therefore cannot recycle its ID.
        PaperIdAllocationLedger restartedJvm = new PaperIdAllocationLedger(state);
        long next = restartedJvm.reserve(room, 77627897L);

        assertThat(suppressedDraft).isEqualTo(77627898L);
        assertThat(next).isEqualTo(77627899L);
        assertThat(restartedJvm.highestReserved(room)).isEqualTo(77627899L);
    }

    @Test
    void legacyPublishedFloorWinsDuringFirstDeployment() {
        UUID room = UUID.fromString("a3289005-1e0d-4b6e-9704-c46b3d9e13d7");
        PaperIdAllocationLedger ledger = new PaperIdAllocationLedger(state);

        assertThat(ledger.reserve(room, 900L)).isEqualTo(901L);
        assertThat(ledger.reserve(room, 950L)).isEqualTo(951L);
    }

    @Test
    void malformedLinesDoNotEraseTheDurableHighWaterMark() throws Exception {
        UUID room = UUID.fromString("b72c4e10-007e-4e09-a75a-72861893ebe7");
        PaperIdAllocationLedger ledger = new PaperIdAllocationLedger(state);
        assertThat(ledger.reserve(room, 10L)).isEqualTo(11L);
        Path log = state.resolve(room + ".paper-id-allocations.log");
        Files.writeString(log, "not an allocation\nP-b72c-not-a-number\n",
                java.nio.file.StandardOpenOption.APPEND);

        assertThat(new PaperIdAllocationLedger(state).reserve(room, 10L)).isEqualTo(12L);
    }
}
