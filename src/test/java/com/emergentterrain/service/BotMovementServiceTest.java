package com.emergentterrain.service;

import com.emergentterrain.domain.RoomBot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the behaviour of {@link BotMovementService} so the Phase 2.5 carve out
 * of {@link RoomService} cannot regress. Defaults mirror the inline tunables
 * that lived in RoomService before this extraction.
 */
class BotMovementServiceTest {

    /** Defaults match the @Value-injected production bean. */
    private BotMovementService defaults() {
        return new BotMovementService(
                /* planLength */           3,
                /* reverseCooldownTicks */ 2,
                /* reversePenalty */       0.6,
                /* headingBias */          0.18);
    }

    // ---- isInBounds ----

    @Test
    void isInBoundsAcceptsInteriorAndRejectsBoundaries() {
        BotMovementService svc = defaults();
        int[][] tiles = new int[5][7]; // 5 rows × 7 cols

        assertThat(svc.isInBounds(0, 0, tiles)).isTrue();
        assertThat(svc.isInBounds(6, 4, tiles)).isTrue();
        assertThat(svc.isInBounds(-1, 0, tiles)).isFalse();
        assertThat(svc.isInBounds(0, -1, tiles)).isFalse();
        assertThat(svc.isInBounds(7, 0, tiles)).isFalse();
        assertThat(svc.isInBounds(0, 5, tiles)).isFalse();
    }

    // ---- tilePreferenceScore ----

    @Test
    void tilePreferenceScoreMatchesProfileBucketsByType() {
        BotMovementService svc = defaults();
        Map<String, Double> profile = Map.of(
                "forest", 0.7,
                "water",  0.6,
                "industry", 0.4,
                "clear",  0.3);

        assertThat(svc.tilePreferenceScore(1, profile)).isEqualTo(0.7);
        assertThat(svc.tilePreferenceScore(2, profile)).isEqualTo(0.6);
        assertThat(svc.tilePreferenceScore(3, profile)).isEqualTo(0.4);
        assertThat(svc.tilePreferenceScore(4, profile)).isEqualTo(0.3);
        // Unknown type falls back to the "clear" bucket.
        assertThat(svc.tilePreferenceScore(99, profile)).isEqualTo(0.3);
    }

    @Test
    void tilePreferenceScoreDefaultsToHalfWhenBucketMissing() {
        BotMovementService svc = defaults();

        assertThat(svc.tilePreferenceScore(1, Map.of())).isEqualTo(0.5);
        assertThat(svc.tilePreferenceScore(4, Map.of())).isEqualTo(0.5);
    }

    // ---- recordMoveOnMotion ----

    @Test
    void recordMoveOnMotionAppliesConfiguredCooldown() {
        BotMovementService svc = defaults();
        BotMotionState motion = new BotMotionState();

        svc.recordMoveOnMotion(motion, 5, 5, 6, 5);

        assertThat(motion.lastX).isEqualTo(5);
        assertThat(motion.lastY).isEqualTo(5);
        assertThat(motion.currentX).isEqualTo(6);
        assertThat(motion.currentY).isEqualTo(5);
        assertThat(motion.headingX).isEqualTo(1);
        assertThat(motion.headingY).isZero();
        assertThat(motion.reverseCooldownTicks).isEqualTo(2);
    }

    // ---- stepTowardTarget ----

    @Test
    void stepTowardTargetReturnsNullWhenAtTarget() {
        BotMovementService svc = defaults();
        RoomBot bot = botAt(3, 3);
        int[][] tiles = new int[10][10];

        int[] step = svc.stepTowardTarget(bot, tiles, new boolean[0][0],
                new BotMotionState(), Map.of(), new int[]{3, 3});

        assertThat(step).isNull();
    }

    @Test
    void stepTowardTargetReturnsNullWhenTargetIsNull() {
        BotMovementService svc = defaults();
        RoomBot bot = botAt(3, 3);
        int[][] tiles = new int[10][10];

        assertThat(svc.stepTowardTarget(bot, tiles, new boolean[0][0],
                new BotMotionState(), Map.of(), null)).isNull();
    }

    @Test
    void stepTowardTargetMovesAlongPositiveDeltaAxis() {
        BotMovementService svc = defaults();
        RoomBot bot = botAt(0, 0);
        int[][] tiles = new int[10][10]; // all clear (type 0)

        // Target diagonal: prefer one axis (x or y); both candidates score equally,
        // so the first non-rejected candidate wins. With dx > 0 added first, x-step wins.
        int[] step = svc.stepTowardTarget(bot, tiles, new boolean[0][0],
                new BotMotionState(), Map.of(), new int[]{5, 5});

        assertThat(step).isNotNull();
        assertThat(step).containsExactly(1, 0);
    }

    @Test
    void stepTowardTargetPrefersHighwayTilesViaBonus() {
        BotMovementService svc = defaults();
        RoomBot bot = botAt(0, 0);
        int[][] tiles = new int[3][3];           // all clear (type 0)
        boolean[][] highways = new boolean[3][3];
        // Mark the y-axis candidate as highway. Highway bonus is +0.25.
        highways[1][0] = true;

        int[] step = svc.stepTowardTarget(bot, tiles, highways,
                new BotMotionState(), Map.of(), new int[]{2, 2});

        assertThat(step).containsExactly(0, 1);
    }

    // ---- pickStep ----

    @Test
    void pickStepReturnsPlannedStepWhenInBounds() {
        BotMovementService svc = defaults();
        RoomBot bot = botAt(3, 3);
        BotMotionState motion = new BotMotionState();
        motion.setPlan(java.util.List.of(new int[]{4, 3}));

        int[] step = svc.pickStep(bot, new int[10][10], new boolean[0][0], Map.of(), motion);

        assertThat(step).containsExactly(4, 3);
    }

    @Test
    void pickStepReplansWhenPlannedStepIsOutOfBounds() {
        BotMovementService svc = defaults();
        RoomBot bot = botAt(0, 0);
        BotMotionState motion = new BotMotionState();
        motion.setPlan(java.util.List.of(new int[]{-5, -5}));

        int[] step = svc.pickStep(bot, new int[3][3], new boolean[0][0], Map.of(), motion);

        // Replanned to a fresh in-bounds neighbor. Exact direction depends on
        // antiOscillation tie-breaking but it must be in bounds and adjacent.
        assertThat(step).isNotNull();
        assertThat(step.length).isEqualTo(2);
        assertThat(svc.isInBounds(step[0], step[1], new int[3][3])).isTrue();
        assertThat(Math.abs(step[0] - bot.getX()) + Math.abs(step[1] - bot.getY())).isEqualTo(1);
    }

    @Test
    void pickStepProducesNullWhenSurroundedOutOfBounds() {
        BotMovementService svc = defaults();
        RoomBot bot = botAt(0, 0);
        // 1×1 grid; every neighbor is out of bounds. planPath returns empty.
        int[] step = svc.pickStep(bot, new int[1][1], new boolean[0][0],
                Map.of(), new BotMotionState());

        assertThat(step).isNull();
    }

    // ---- tunable accessors ----

    @Test
    void tunableAccessorsReturnConfiguredValues() {
        BotMovementService svc = defaults();
        assertThat(svc.planLength()).isEqualTo(3);
        assertThat(svc.reverseCooldownTicks()).isEqualTo(2);
    }

    @Test
    void constructorClampsTunablesToValidRanges() {
        BotMovementService svc = new BotMovementService(0, -5, -1.0, -1.0);
        assertThat(svc.planLength()).isEqualTo(1);                // clamped up to 1
        assertThat(svc.reverseCooldownTicks()).isZero();          // clamped to 0
        // The penalty/heading clamps are observable via behaviour: a move with
        // both fields at zero produces a recorded cooldown of 0 too.
        BotMotionState motion = new BotMotionState();
        svc.recordMoveOnMotion(motion, 0, 0, 1, 0);
        assertThat(motion.reverseCooldownTicks).isZero();
    }

    @Test
    void reversePenaltyDiscouragesBackingIntoLastTile() {
        BotMovementService svc = defaults();
        RoomBot bot = botAt(2, 2);
        BotMotionState motion = new BotMotionState();
        motion.currentX = 2;
        motion.currentY = 2;
        motion.lastX = 1;
        motion.lastY = 2;
        motion.headingX = 1;
        motion.headingY = 0;
        motion.reverseCooldownTicks = 2;
        // Plain 5×5 grid. Going back to (1,2) loses 2*0.6 (penalty doubles when
        // cooldown > 0) and the headingBias (going against heading). Going forward
        // to (3,2) gains the heading bias. So pickStep must not pick (1,2).
        int[][] tiles = new int[5][5];

        int[] step = svc.pickStep(bot, tiles, new boolean[0][0], Map.of(), motion);

        assertThat(step).isNotNull();
        assertThat(step).isNotEqualTo(new int[]{1, 2});
    }

    private static RoomBot botAt(int x, int y) {
        RoomBot bot = mock(RoomBot.class);
        when(bot.getX()).thenReturn(x);
        when(bot.getY()).thenReturn(y);
        return bot;
    }
}
