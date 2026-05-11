package com.emergentterrain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2.5 carve: per-bot movement state — current position, last position,
 * heading vector, reverse cooldown counter, and a bounded queue of planned
 * next steps. Persisted in {@code RoomBot.stateJson} via the
 * {@code RoomService.readBotMotionState / writeBotMotionState} pair, and
 * codec-serialized into the GPU bot-state buffer via
 * {@code RoomService.encodeBotMotionStateBytes / decodeBotMotionStateBytes}.
 *
 * Promoted from a private nested class on {@link RoomService} so the new
 * {@link BotMovementService} can read/write it without depending on a
 * RoomService inner class. Field shape and visibility preserved verbatim
 * so the existing JSON / binary round-trips behave identically.
 *
 * Mutability is intentional — the read/write cycle for a single bot tick
 * mutates this object in place, then serialises it back to the bot row.
 */
class BotMotionState {

    public Integer currentX;
    public Integer currentY;
    public Integer lastX;
    public Integer lastY;
    public int     headingX;
    public int     headingY;
    public int     reverseCooldownTicks;
    public List<int[]> plannedSteps = new ArrayList<>();

    void tick() {
        if (reverseCooldownTicks > 0) reverseCooldownTicks--;
    }

    void normalize() {
        if (plannedSteps == null) plannedSteps = new ArrayList<>();
        if (plannedSteps.size() > 2) plannedSteps = new ArrayList<>(plannedSteps.subList(0, 2));
    }

    void resetAt(Integer x, Integer y) {
        currentX = x;
        currentY = y;
        lastX = null;
        lastY = null;
        headingX = 0;
        headingY = 0;
        reverseCooldownTicks = 0;
        clearPlan();
    }

    void recordMove(int fromX, int fromY, int toX, int toY, int cooldownTicks) {
        lastX = fromX;
        lastY = fromY;
        currentX = toX;
        currentY = toY;
        headingX = Integer.compare(toX, fromX);
        headingY = Integer.compare(toY, fromY);
        reverseCooldownTicks = cooldownTicks;
    }

    void setPlan(List<int[]> nextSteps) {
        plannedSteps = new ArrayList<>();
        if (nextSteps == null) return;
        for (int i = 0; i < Math.min(2, nextSteps.size()); i++) {
            int[] step = nextSteps.get(i);
            if (step != null && step.length >= 2) {
                plannedSteps.add(new int[] { step[0], step[1] });
            }
        }
    }

    boolean hasPlannedStep() {
        return plannedSteps != null && !plannedSteps.isEmpty();
    }

    int[] peekPlannedStep() {
        return hasPlannedStep() ? plannedSteps.get(0) : null;
    }

    void consumePlannedStep() {
        if (hasPlannedStep()) plannedSteps.remove(0);
    }

    void clearPlan() {
        plannedSteps = new ArrayList<>();
    }

    BotMotionState copy() {
        BotMotionState copy = new BotMotionState();
        copy.currentX = currentX;
        copy.currentY = currentY;
        copy.lastX = lastX;
        copy.lastY = lastY;
        copy.headingX = headingX;
        copy.headingY = headingY;
        copy.reverseCooldownTicks = reverseCooldownTicks;
        copy.setPlan(plannedSteps);
        return copy;
    }
}
