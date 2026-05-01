package com.emergentterrain.research;

/**
 * LLM-suggested search parameters for the bot's next paper in the same domain+topic.
 * Stored in BotLifecycleState.pendingFollowUp and consumed once at the start of the
 * next research cycle for that bot, replacing the tick-seed-derived defaults.
 *
 * param1 (10–99): shifts the starting position of the parameter search.
 * param2 (1.0–9.0): scales a secondary dimension (depth, temperature, capacitance factor).
 */
public record FollowUpParams(int param1, double param2) {}
