package com.emergentterrain.service;

import com.emergentterrain.domain.RoomBot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Phase 2.5 carve: bot archetype dispatch and rule evaluation, lifted out of
 * {@link RoomService} ahead of the broader RoomService refactor.
 *
 * Seven archetypes (STEWARD, BUILDER, FLOW_KEEPER, PRESERVATIONIST, PRAGMATIST,
 * CONTRARIAN, NEIGHBOR_MIRROR) each define:
 *  - a target tile-share profile ({@link #targetsFor(String)}) that drives a
 *    "prosperity" delta against an observed {@link LocalStats},
 *  - a set of tile types they actively prefer ({@link #prefersType}), and
 *  - a set of contextual veto rules ({@link #dislikesType}).
 *
 * The service is stateless and deterministic for a given (bot, archetype index)
 * pair — assignment is a stable hash over {@code (botId, type)}, the same scheme
 * used previously by the inline {@code RoomService.botArchetype} method so
 * existing rooms keep their archetype distribution unchanged after this carve.
 *
 * Pure refactor. No behavioural change. The phase-2.5 doc explicitly calls this
 * extraction "almost certainly correct regardless of how Phase 3 design lands"
 * — it ships independently of the rest of the planned RoomService split.
 */
@Service
public class BotArchetypeBehaviorService {

    static final List<String> ARCHETYPES = List.of(
        "STEWARD", "BUILDER", "FLOW_KEEPER", "PRESERVATIONIST",
        "PRAGMATIST", "CONTRARIAN", "NEIGHBOR_MIRROR"
    );

    /** Stable archetype assignment hashed on (botId, type). Same scheme as pre-carve. */
    public String archetypeFor(RoomBot bot) {
        int idx = Math.abs(Objects.hash(bot.getId(), bot.getType())) % ARCHETYPES.size();
        return ARCHETYPES.get(idx);
    }

    /** Sum of absolute deviations between observed shares and the archetype's target profile. */
    public double prosperityScore(LocalStats stats, String archetype) {
        double[] targets = targetsFor(archetype);
        return Math.abs(stats.forest()   - targets[0])
             + Math.abs(stats.water()    - targets[1])
             + Math.abs(stats.clear()    - targets[2])
             + Math.abs(stats.industry() - targets[3])
             + Math.abs(stats.road()     - targets[4]);
    }

    /** Target tile-share profile per archetype: [forest, water, clear, industry, road]. */
    public double[] targetsFor(String archetype) {
        return switch (archetype) {
            case "STEWARD"         -> new double[] { 0.36, 0.16, 0.32, 0.12, 0.04 };
            case "BUILDER"         -> new double[] { 0.22, 0.10, 0.30, 0.30, 0.08 };
            case "FLOW_KEEPER"     -> new double[] { 0.24, 0.20, 0.36, 0.16, 0.04 };
            case "PRESERVATIONIST" -> new double[] { 0.42, 0.18, 0.26, 0.10, 0.04 };
            case "PRAGMATIST"      -> new double[] { 0.30, 0.15, 0.34, 0.17, 0.04 };
            case "CONTRARIAN"      -> new double[] { 0.28, 0.16, 0.34, 0.18, 0.04 };
            case "NEIGHBOR_MIRROR" -> new double[] { 0.30, 0.14, 0.36, 0.16, 0.04 };
            default                -> new double[] { 0.30, 0.15, 0.34, 0.17, 0.04 };
        };
    }

    /** True if the archetype actively prefers placing tiles of the given type. */
    public boolean prefersType(String archetype, int type) {
        return switch (archetype) {
            case "STEWARD"         -> (type == 1 || type == 2);
            case "BUILDER"         -> (type == 3 || type == 0);
            case "FLOW_KEEPER"     -> (type == 2 || type == 0);
            case "PRESERVATIONIST" -> (type == 1);
            case "PRAGMATIST", "CONTRARIAN", "NEIGHBOR_MIRROR" -> true;
            default                -> true;
        };
    }

    /** True if a contextual veto applies for this archetype against placing this tile type. */
    public boolean dislikesType(String archetype, int type, LocalStats before) {
        return switch (archetype) {
            case "STEWARD"         -> type == 3 && (before.forest() < 0.25 || before.water() < 0.10);
            case "BUILDER"         -> (type == 1 || type == 2) && before.industry() < 0.18;
            case "FLOW_KEEPER"     -> type == 3 && before.water() < 0.12;
            case "PRESERVATIONIST" -> (type == 0 || type == 3) && before.forest() < 0.32;
            default                -> false;
        };
    }
}
