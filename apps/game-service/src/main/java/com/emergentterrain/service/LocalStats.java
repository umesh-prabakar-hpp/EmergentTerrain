package com.emergentterrain.service;

/**
 * Aggregate tile-share statistics over a viewport around an anchor in a room.
 *
 * Phase 2.5 carve: lifted out of {@link RoomService} so the new
 * {@link BotArchetypeBehaviorService} (and any other extracted services that
 * read/score local terrain context) can take it as input without depending on a
 * private inner class.
 *
 * Each field is the share of tiles of that kind in the viewport, in [0, 1].
 * {@code total} is the absolute viewport tile count and is only used to detect
 * the empty-viewport case.
 */
record LocalStats(int total,
                  double forest,
                  double water,
                  double clear,
                  double industry,
                  double road) {}
