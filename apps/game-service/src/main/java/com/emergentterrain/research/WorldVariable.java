package com.emergentterrain.research;

import java.util.UUID;

/**
 * Phase 3 Iter 6 — one observable simulation variable. The basic unit a bot
 * can read, hypothesise about, and (post-Phase-2.5) intervene on.
 *
 * The {@link Scope} captures which slice of the simulation the value belongs
 * to. GLOBAL covers room-wide aggregates (federal trust, total budget); the
 * REGION / DISTRICT / BOT scopes are placeholders for future per-area queries.
 *
 * Values are always {@code double} for now — discrete categorical variables
 * (e.g. dominant party id) can be surfaced later as separate enum-typed
 * variables; that's a pragmatic restriction, not a fundamental one.
 */
public record WorldVariable(
        String  name,
        Scope   scope,
        double  value,
        long    observedAtTick) {

    /** Convenience constructor for the most common case (room-global). */
    public static WorldVariable global(String name, double value, long tick) {
        return new WorldVariable(name, Scope.global(), value, tick);
    }

    /** Slice of the simulation a variable belongs to. */
    public record Scope(Kind kind, UUID id) {

        public enum Kind { GLOBAL, REGION, DISTRICT, BOT }

        public static Scope global()              { return new Scope(Kind.GLOBAL, null); }
        public static Scope region(UUID id)       { return new Scope(Kind.REGION,   id); }
        public static Scope district(UUID id)     { return new Scope(Kind.DISTRICT, id); }
        public static Scope bot(UUID id)          { return new Scope(Kind.BOT,      id); }
    }
}
