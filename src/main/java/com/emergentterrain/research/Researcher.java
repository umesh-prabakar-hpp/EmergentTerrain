package com.emergentterrain.research;

import java.util.Random;

/**
 * Thin adapter wrapping a RoomBot's BotLifecycleState for use inside
 * UniversityService research cycles. Delegates all genome and stat
 * operations to the backing BotLifecycleState so changes are reflected
 * in the persistent state.
 */
public class Researcher {

    // Kept for external references that may still use the constants
    public static final String[] DOMAINS      = BotGenome.DOMAINS;
    public static final String[] METHODOLOGIES = BotGenome.METHODOLOGIES;

    private final String           botId;
    private final BotLifecycleState state;

    public Researcher(String botId, BotLifecycleState state) {
        this.botId = botId;
        this.state = state;
    }

    public int selectTopic(Random rng) {
        return state.getGenome().selectTopic(rng);
    }

    public double errorRate()   { return state.errorRate(); }
    public double fitness()     { return state.fitness(); }
    public String getDomain()   { return state.getGenome().getDomain(); }
    public String getMethodology() { return state.getGenome().getMethodology(); }
    public double[] getTopicWeights() { return state.getGenome().getTopicWeights(); }
    public double getExplorationRate() { return state.getGenome().getExplorationRate(); }
    public int    getGeneration()   { return state.getGenome().getGeneration(); }
    public int    getPublishedCount() { return state.getPublishedCount(); }
    public int    getRejectedCount()  { return state.getRejectedCount(); }
    public double getReputation()     { return state.getReputation(); }
    public String getBotId()          { return botId; }

    public void recordPublished(double score) { state.recordPublished(score); }
    public void recordRejected()              { state.recordRejected(); }

    public BotLifecycleState getState() { return state; }

    /** Crossover two researchers, producing a child BotGenome. */
    public static BotGenome crossoverGenome(Researcher a, Researcher b, Random rng) {
        return BotGenome.crossover(a.state.getGenome(), b.state.getGenome(), rng,
                                   a.fitness(), b.fitness());
    }
}
