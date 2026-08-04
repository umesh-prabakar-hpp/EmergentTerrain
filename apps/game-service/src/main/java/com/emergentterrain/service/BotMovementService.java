package com.emergentterrain.service;

import com.emergentterrain.domain.RoomBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Phase 2.5 carve: pure path-planning + scoring helpers, lifted out of
 * {@link RoomService} ahead of the broader RoomService refactor.
 *
 * Three responsibilities live here:
 * <ul>
 *   <li><b>Tile-type preference scoring</b> — maps a bot's profile against a
 *       proposed tile type. Used by both the movement loop and
 *       {@link RoomService#botVoteDecision}.</li>
 *   <li><b>Per-step decision</b> — {@link #pickStep} chooses the next move
 *       (consuming a planned step if one exists, else replanning) and
 *       {@link #stepTowardTarget} picks a single greedy step toward an
 *       externally-supplied relocation target.</li>
 *   <li><b>Motion-state mutation</b> — {@link #recordMoveOnMotion} applies
 *       a move to a {@link BotMotionState} using the configured reverse
 *       cooldown so callers don't have to know the constant.</li>
 * </ul>
 *
 * Pure refactor. No behavioural change — all four
 * {@code app.bots.{planLength,reverseCooldownTicks,reversePenalty,headingBias}}
 * tunables move here verbatim with the same defaults; the path-planning
 * algorithms are byte-for-byte equivalent to the inline versions that
 * previously lived in {@code RoomService}.
 *
 * Stays narrow on purpose: orchestration ({@code tickBots}, occupancy
 * checks, persistence), spawn placement, and relocation triggering all
 * remain in {@code RoomService} because they touch the room-state cache
 * map, the participant/bot repositories, and the proposal-vote machinery
 * — none of which belong with movement math.
 */
@Service
public class BotMovementService {

    private final int    planLength;
    private final int    reverseCooldownTicks;
    private final double reversePenalty;
    private final double headingBias;

    public BotMovementService(
            @Value("${app.bots.planLength:3}")              int planLength,
            @Value("${app.bots.reverseCooldownTicks:2}")    int reverseCooldownTicks,
            @Value("${app.bots.reversePenalty:0.6}")        double reversePenalty,
            @Value("${app.bots.headingBias:0.18}")          double headingBias) {
        this.planLength           = Math.max(1, planLength);
        this.reverseCooldownTicks = Math.max(0, reverseCooldownTicks);
        this.reversePenalty       = Math.max(0d, reversePenalty);
        this.headingBias          = Math.max(0d, headingBias);
    }

    public int planLength()           { return planLength; }
    public int reverseCooldownTicks() { return reverseCooldownTicks; }

    /** True when (x, y) is inside the {@code tiles} grid. */
    public boolean isInBounds(int x, int y, int[][] tiles) {
        return y >= 0 && y < tiles.length && x >= 0 && x < tiles[0].length;
    }

    /**
     * Phase 2.5 follow-up — place {@code bot} at a random valid coordinate in
     * the world. Tries up to 1000 random positions, accepting the first one
     * that passes {@code canOccupy}; falls back to the world centre if no
     * candidate qualifies (e.g. densely populated rooms). The RNG is seeded
     * deterministically from {@code (roomId, botType, height, width)} so the
     * same bot always lands in the same spot under identical world state —
     * matches the pre-carve behaviour.
     *
     * The {@code canOccupy} predicate is supplied by the caller so this
     * service stays free of the {@code roomStateCache} / occupancy-snapshot
     * machinery. {@link RoomService}'s thin wrapper passes
     * {@code (x, y) -> canOccupy(bot.getRoom(), x, y, botIdString)}.
     */
    public void assignBotSpawn(RoomBot bot, int[][] tiles, BiPredicate<Integer, Integer> canOccupy) {
        int h = tiles.length;
        int w = tiles[0].length;
        UUID roomId = bot.getRoom() == null ? null : bot.getRoom().getId();
        Random rand = new Random(Objects.hash(roomId, bot.getType(), h, w));
        for (int i = 0; i < 1000; i++) {
            int x = rand.nextInt(w);
            int y = rand.nextInt(h);
            if (canOccupy.test(x, y)) {
                bot.setX(x);
                bot.setY(y);
                return;
            }
        }
        bot.setX(w / 2);
        bot.setY(h / 2);
    }

    /** Profile-driven preference score for placing on a tile of the given type. */
    public double tilePreferenceScore(int type, Map<String, Double> profile) {
        return switch (type) {
            case 1  -> profile.getOrDefault("forest",   0.5);
            case 2  -> profile.getOrDefault("water",    0.5);
            case 3  -> profile.getOrDefault("industry", 0.5);
            case 4  -> profile.getOrDefault("clear",    0.5);
            default -> profile.getOrDefault("clear",    0.5);
        };
    }

    /**
     * Choose the bot's next step. Reuses any in-flight planned step if it is
     * still in-bounds; otherwise replans a fresh path of up to
     * {@link #planLength} steps and returns the first one.
     */
    public int[] pickStep(RoomBot bot,
                          int[][] tiles,
                          boolean[][] highways,
                          Map<String, Double> profile,
                          BotMotionState motion) {
        if (motion.hasPlannedStep()) {
            int[] planned = motion.peekPlannedStep();
            if (isInBounds(planned[0], planned[1], tiles)) return planned;
            motion.clearPlan();
        }
        List<int[]> plannedPath = planPath(bot.getX(), bot.getY(),
                tiles, highways, profile, motion, planLength);
        motion.setPlan(plannedPath);
        return motion.peekPlannedStep();
    }

    /**
     * Pick a single greedy step toward an externally-chosen target tile.
     * Returns null when the bot is already at the target, when both
     * x-axis and y-axis candidates are out of bounds, or when no candidate
     * scored above {@code -Double.MAX_VALUE}.
     */
    public int[] stepTowardTarget(RoomBot bot,
                                   int[][] tiles,
                                   boolean[][] highways,
                                   BotMotionState motion,
                                   Map<String, Double> profile,
                                   int[] best) {
        if (best == null) return null;
        int dx = best[0] - bot.getX();
        int dy = best[1] - bot.getY();
        if (dx == 0 && dy == 0) return null;
        List<int[]> candidates = new ArrayList<>();
        if (dx != 0) candidates.add(new int[] { bot.getX() + Integer.signum(dx), bot.getY() });
        if (dy != 0) candidates.add(new int[] { bot.getX(), bot.getY() + Integer.signum(dy) });
        int[] bestStep = null;
        double bestScore = -Double.MAX_VALUE;
        for (int[] c : candidates) {
            if (c[0] < 0 || c[0] >= tiles[0].length || c[1] < 0 || c[1] >= tiles.length) continue;
            double score = tilePreferenceScore(tiles[c[1]][c[0]], profile);
            score += antiOscillationAdjustment(c[0], c[1], tiles, highways, motion);
            if ((highways.length > 0 && highways[c[1]][c[0]]) || tiles[c[1]][c[0]] == 4) score += 0.25;
            if (score > bestScore) {
                bestScore = score;
                bestStep = c;
            }
        }
        return bestStep;
    }

    /**
     * Apply a move to {@code motion} using the service-configured reverse
     * cooldown. Lets {@link RoomService#applyBotMove(RoomBot, BotMotionState, int, int)}
     * stay agnostic of the tunable.
     */
    public void recordMoveOnMotion(BotMotionState motion, int fromX, int fromY, int toX, int toY) {
        motion.recordMove(fromX, fromY, toX, toY, reverseCooldownTicks);
    }

    /**
     * Pick a relocation target tile for a bot whose current viewport no longer
     * fits its profile. Two-stage search: first scan the coarse cells produced
     * by {@link MapIndexService#getCachedMap} for the cell whose tile-share
     * mix best matches the profile (with a small distance bias toward the
     * current position), then refine with a tile-by-tile sweep within the
     * coarse cell's viewport using the integral maps for O(1) window sums.
     *
     * Returns {@code [bestX, bestY]}; never null. When the bot is already at
     * the optimum (or no candidate beats the starting position), the bot's
     * current position is returned, which {@link #stepTowardTarget} treats
     * as a no-op.
     */
    public int[] findBestProfileTarget(int currentX,
                                        int currentY,
                                        int[][] tiles,
                                        Map<String, Double> profile,
                                        CachedMap cached,
                                        MapIndexService mapIndex) {
        double tForest   = profile.getOrDefault("forest",   0.25);
        double tWater    = profile.getOrDefault("water",    0.15);
        double tClear    = profile.getOrDefault("clear",    0.35);
        double tIndustry = profile.getOrDefault("industry", 0.15);
        double tRoad     = 0.02 + profile.getOrDefault("mobility", 0.5) * 0.15;
        int h = tiles.length;
        int w = tiles[0].length;
        double bestScore = Double.MAX_VALUE;
        int bestX = currentX;
        int bestY = currentY;
        for (CoarseCell cell : cached.cells) {
            double score = Math.abs(cell.forest   - tForest)
                         + Math.abs(cell.water    - tWater)
                         + Math.abs(cell.clear    - tClear)
                         + Math.abs(cell.industry - tIndustry)
                         + Math.abs(cell.road     - tRoad);
            int dist = Math.abs(cell.x - currentX) + Math.abs(cell.y - currentY);
            score += dist * 0.001;
            if (score < bestScore) {
                bestScore = score;
                bestX = cell.x;
                bestY = cell.y;
            }
        }
        int refine = Math.max(4, cached.step);
        int viewW = cached.viewW;
        int viewH = cached.viewH;
        for (int y = Math.max(0, bestY - refine); y <= Math.min(h - 1, bestY + refine); y++) {
            for (int x = Math.max(0, bestX - refine); x <= Math.min(w - 1, bestX + refine); x++) {
                int viewX = Math.max(0, Math.min(w - viewW, x - viewW / 2));
                int viewY = Math.max(0, Math.min(h - viewH, y - viewH / 2));
                int x0 = viewX;
                int y0 = viewY;
                int x1 = viewX + viewW;
                int y1 = viewY + viewH;
                int total = viewW * viewH;
                if (total == 0) continue;
                double forest   = mapIndex.rectSum(cached.integrals.forest,   x0, y0, x1, y1) / (double) total;
                double water    = mapIndex.rectSum(cached.integrals.water,    x0, y0, x1, y1) / (double) total;
                double clear    = mapIndex.rectSum(cached.integrals.clear,    x0, y0, x1, y1) / (double) total;
                double industry = mapIndex.rectSum(cached.integrals.industry, x0, y0, x1, y1) / (double) total;
                double road     = mapIndex.rectSum(cached.integrals.road,     x0, y0, x1, y1) / (double) total;
                double score = Math.abs(forest   - tForest)
                             + Math.abs(water    - tWater)
                             + Math.abs(clear    - tClear)
                             + Math.abs(industry - tIndustry)
                             + Math.abs(road     - tRoad);
                int dist = Math.abs(x - currentX) + Math.abs(y - currentY);
                score += dist * 0.001;
                if (score < bestScore) {
                    bestScore = score;
                    bestX = x;
                    bestY = y;
                }
            }
        }
        return new int[] { bestX, bestY };
    }

    // ---- internals ----

    private List<int[]> planPath(int startX,
                                  int startY,
                                  int[][] tiles,
                                  boolean[][] highways,
                                  Map<String, Double> profile,
                                  BotMotionState motion,
                                  int maxSteps) {
        List<int[]> path = new ArrayList<>();
        int currentX = startX;
        int currentY = startY;
        BotMotionState scratch = motion.copy();
        for (int i = 0; i < maxSteps; i++) {
            int[] next = chooseBestNeighbor(currentX, currentY, tiles, highways, profile, scratch);
            if (next == null || (next[0] == currentX && next[1] == currentY)) break;
            path.add(next);
            scratch.recordMove(currentX, currentY, next[0], next[1], reverseCooldownTicks);
            currentX = next[0];
            currentY = next[1];
        }
        return path;
    }

    private int[] chooseBestNeighbor(int x,
                                      int y,
                                      int[][] tiles,
                                      boolean[][] highways,
                                      Map<String, Double> profile,
                                      BotMotionState motion) {
        int[][] dirs = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        double best = -Double.MAX_VALUE;
        int[] bestPos = null;
        for (int[] d : dirs) {
            int nx = x + d[0];
            int ny = y + d[1];
            if (!isInBounds(nx, ny, tiles)) continue;
            int type = tiles[ny][nx];
            double score = tilePreferenceScore(type, profile);
            if ((highways.length > 0 && highways[ny][nx]) || type == 4) score += 0.2;
            score += antiOscillationAdjustment(nx, ny, tiles, highways, motion);
            if (score > best) {
                best = score;
                bestPos = new int[] { nx, ny };
            }
        }
        return bestPos;
    }

    private double antiOscillationAdjustment(int nx,
                                              int ny,
                                              int[][] tiles,
                                              boolean[][] highways,
                                              BotMotionState motion) {
        double score = 0d;
        if (motion.lastX != null && motion.lastY != null
                && motion.lastX == nx && motion.lastY == ny) {
            score -= reversePenalty;
            if (motion.reverseCooldownTicks > 0) score -= reversePenalty;
        }
        if (motion.headingX != 0 || motion.headingY != 0) {
            int dx = nx - motion.currentX;
            int dy = ny - motion.currentY;
            if (dx == motion.headingX && dy == motion.headingY) score += headingBias;
            else if (dx == -motion.headingX && dy == -motion.headingY) score -= headingBias;
        }
        return score;
    }
}
