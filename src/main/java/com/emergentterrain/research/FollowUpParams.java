package com.emergentterrain.research;

/**
 * LLM-suggested search parameters for the bot's next paper in the same domain+topic.
 * Stored in BotLifecycleState.pendingFollowUp and consumed once at the start of the
 * next research cycle for that bot, replacing the tick-seed-derived defaults.
 *
 * param1 (10–99): shifts the starting position of the parameter search.
 * param2 (1.0–9.0): scales a secondary dimension (depth, temperature, capacitance factor).
 * predictedScore: LLM's quality prediction for the follow-up result (0.0–1.0, nullable).
 * priorMethodology: methodology of the paper that generated this follow-up (for chain framing).
 */
public record FollowUpParams(int param1, double param2,
                              Double predictedScore, String priorMethodology) {
    public FollowUpParams(int param1, double param2) {
        this(param1, param2, null, null);
    }
}
