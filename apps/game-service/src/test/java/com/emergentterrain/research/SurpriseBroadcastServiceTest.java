package com.emergentterrain.research;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for Phase 4b SurpriseBroadcastService — verifies the disabled-flag
 * no-op invariant, the per-room cooldown, the bounded recent-buffer, and
 * the bot-exclusion read path.
 *
 * Tests that exercise multi-event ordering / capacity use a 0-second cooldown
 * so they don't have to wait wall-clock time.
 */
class SurpriseBroadcastServiceTest {

    @Test
    void disabledServiceIsNoOp() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(false, 30);
        UUID room = UUID.randomUUID();
        assertThat(svc.maybeBroadcast(event(room, "P-1"))).isFalse();
        assertThat(svc.recentBroadcasts(room, null, 5)).isEmpty();
        assertThat(svc.recentBroadcastCount(room)).isZero();
    }

    @Test
    void enabledServiceBroadcastsFirstEvent() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(true, 30);
        UUID room = UUID.randomUUID();

        assertThat(svc.maybeBroadcast(event(room, "P-1"))).isTrue();
        assertThat(svc.recentBroadcastCount(room)).isEqualTo(1);
    }

    @Test
    void secondBroadcastWithinCooldownIsSuppressed() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(true, 30);
        UUID room = UUID.randomUUID();

        assertThat(svc.maybeBroadcast(event(room, "P-1"))).isTrue();
        // Second call is immediately after the first → cooldown is active.
        assertThat(svc.maybeBroadcast(event(room, "P-2"))).isFalse();
        assertThat(svc.recentBroadcastCount(room)).isEqualTo(1);
    }

    @Test
    void cooldownIsPerRoomNotGlobal() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(true, 30);
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();

        assertThat(svc.maybeBroadcast(event(roomA, "P-1"))).isTrue();
        // Different room — should not be rate-limited.
        assertThat(svc.maybeBroadcast(event(roomB, "P-2"))).isTrue();
        assertThat(svc.recentBroadcastCount(roomA)).isEqualTo(1);
        assertThat(svc.recentBroadcastCount(roomB)).isEqualTo(1);
    }

    @Test
    void nullEventAndNullRoomIdAreNoOps() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(true, 30);
        assertThat(svc.maybeBroadcast(null)).isFalse();
        assertThat(svc.maybeBroadcast(event(null, "P-x"))).isFalse();
    }

    @Test
    void recentBroadcastsReturnsNewestFirst() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(true, 0);  // no cooldown
        UUID room = UUID.randomUUID();

        svc.maybeBroadcast(event(room, "P-1"));
        svc.maybeBroadcast(event(room, "P-2"));
        svc.maybeBroadcast(event(room, "P-3"));

        assertThat(svc.recentBroadcasts(room, null, 10))
                .extracting(SurpriseEvent::getPaperId)
                .containsExactly("P-3", "P-2", "P-1");
    }

    @Test
    void recentBufferIsCappedAtFivePerRoom() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(true, 0);
        UUID room = UUID.randomUUID();

        for (int i = 1; i <= 7; i++) {
            svc.maybeBroadcast(event(room, "P-" + i));
        }

        assertThat(svc.recentBroadcastCount(room)).isEqualTo(5);
        // Newest-first: the 5 most recent are P-7..P-3; P-1 and P-2 were evicted.
        assertThat(svc.recentBroadcasts(room, null, 10))
                .extracting(SurpriseEvent::getPaperId)
                .containsExactly("P-7", "P-6", "P-5", "P-4", "P-3");
    }

    @Test
    void recentBroadcastsRespectsLimit() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(true, 0);
        UUID room = UUID.randomUUID();
        svc.maybeBroadcast(event(room, "P-1"));
        svc.maybeBroadcast(event(room, "P-2"));

        assertThat(svc.recentBroadcasts(room, null, 0)).isEmpty();
        assertThat(svc.recentBroadcasts(room, null, 1))
                .extracting(SurpriseEvent::getPaperId).containsExactly("P-2");
        assertThat(svc.recentBroadcasts(room, null, 10))
                .extracting(SurpriseEvent::getPaperId).containsExactly("P-2", "P-1");
    }

    @Test
    void recentBroadcastsExcludesBotById() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(true, 0);
        UUID room = UUID.randomUUID();
        UUID botA = UUID.randomUUID();
        UUID botB = UUID.randomUUID();
        svc.maybeBroadcast(eventFromBot(room, "P-from-A", botA));
        svc.maybeBroadcast(eventFromBot(room, "P-from-B", botB));

        assertThat(svc.recentBroadcasts(room, botA, 5))
                .extracting(SurpriseEvent::getPaperId).containsExactly("P-from-B");
        assertThat(svc.recentBroadcasts(room, botB, 5))
                .extracting(SurpriseEvent::getPaperId).containsExactly("P-from-A");
    }

    @Test
    void recentBroadcastsRespectsCountAfterExclusion() {
        SurpriseBroadcastService svc = new SurpriseBroadcastService(true, 0);
        UUID room = UUID.randomUUID();
        UUID botA = UUID.randomUUID();
        UUID botB = UUID.randomUUID();
        // Two from A, one from B. Excluding A leaves only the B event.
        svc.maybeBroadcast(eventFromBot(room, "P-from-A-1", botA));
        svc.maybeBroadcast(eventFromBot(room, "P-from-A-2", botA));
        svc.maybeBroadcast(eventFromBot(room, "P-from-B", botB));

        assertThat(svc.recentBroadcasts(room, botA, 5))
                .extracting(SurpriseEvent::getPaperId).containsExactly("P-from-B");
    }

    // ---- helpers ----

    private static SurpriseEvent event(UUID roomId, String paperId) {
        return eventFromBot(roomId, paperId, UUID.randomUUID());
    }

    private static SurpriseEvent eventFromBot(UUID roomId, String paperId, UUID botId) {
        return new SurpriseEvent(
                paperId, botId, roomId, "MATHEMATICS",
                0.5, 3.0,
                SurpriseEvent.Source.PRIOR,
                Instant.now());
    }
}
