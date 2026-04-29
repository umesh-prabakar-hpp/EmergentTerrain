package com.emergentterrain.research;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/**
 * Genome encoding a bot's research search strategy.
 * topicWeights[5] maps to: step-size, restart-probability, candidate-breadth,
 * depth-vs-breadth bias, acceptance-threshold.
 * Stored as part of BotLifecycleState in RoomBot.lifecycleJson.
 */
public class BotGenome {

    public static final String[] DOMAINS      = {"MATHEMATICS", "PHYSICS", "MATERIALS"};
    public static final String[] METHODOLOGIES = {"THEORETICAL", "COMPUTATIONAL", "EMPIRICAL"};
    static final int TOPIC_COUNT = 5;

    private String   domain;
    private String   methodology;
    private double[] topicWeights;
    private double   explorationRate;
    private int      generation;

    // For Jackson deserialisation
    public BotGenome() {}

    public BotGenome(String domain, String methodology, double[] topicWeights,
                     double explorationRate, int generation) {
        this.domain          = domain;
        this.methodology     = methodology;
        this.topicWeights    = clampWeights(topicWeights.clone());
        this.explorationRate = Math.max(0.05, Math.min(0.5, explorationRate));
        this.generation      = generation;
    }

    /** Deterministic initialisation from bot UUID — same bot always gets same starting genome. */
    public static BotGenome initialise(UUID botId, int domainIndex) {
        Random rng = new Random(botId.getLeastSignificantBits() ^ botId.getMostSignificantBits());
        String domain      = DOMAINS[Math.abs(domainIndex) % DOMAINS.length];
        String methodology = METHODOLOGIES[rng.nextInt(METHODOLOGIES.length)];
        double[] weights   = new double[TOPIC_COUNT];
        for (int i = 0; i < TOPIC_COUNT; i++) weights[i] = 0.1 + rng.nextDouble() * 0.8;
        double exploration = 0.1 + rng.nextDouble() * 0.3;
        return new BotGenome(domain, methodology, weights, exploration, 0);
    }

    /** Single-point crossover — child inherits domain from the fitter parent. */
    public static BotGenome crossover(BotGenome a, BotGenome b, Random rng,
                                      double fitnessA, double fitnessB) {
        int point = 1 + rng.nextInt(TOPIC_COUNT - 1);
        double[] childWeights = new double[TOPIC_COUNT];
        System.arraycopy(a.topicWeights, 0, childWeights, 0, point);
        System.arraycopy(b.topicWeights, point, childWeights, point, TOPIC_COUNT - point);
        String method      = rng.nextBoolean() ? a.methodology : b.methodology;
        double exploration = (a.explorationRate + b.explorationRate) / 2.0;
        double total       = fitnessA + fitnessB;
        String domain      = (total < 1e-9 || rng.nextDouble() < fitnessA / (total + 1e-9))
                             ? a.domain : b.domain;
        return new BotGenome(domain, method, childWeights, exploration,
                             Math.max(a.generation, b.generation) + 1);
    }

    /** Averaging merge — used when an elder bot absorbs another. */
    public static BotGenome merge(BotGenome survivor, BotGenome absorbed) {
        double[] merged = new double[TOPIC_COUNT];
        for (int i = 0; i < TOPIC_COUNT; i++) {
            merged[i] = (survivor.topicWeights[i] + absorbed.topicWeights[i]) / 2.0;
        }
        double exploration = Math.min(survivor.explorationRate, absorbed.explorationRate);
        return new BotGenome(survivor.domain, survivor.methodology, merged, exploration,
                             Math.max(survivor.generation, absorbed.generation) + 1);
    }

    /** Gaussian mutation of topic weights and exploration rate. */
    public void mutate(Random rng) {
        for (int i = 0; i < topicWeights.length; i++) {
            if (rng.nextDouble() < explorationRate) {
                topicWeights[i] = Math.max(0.05, Math.min(1.0,
                        topicWeights[i] + rng.nextGaussian() * 0.15));
            }
        }
        explorationRate = Math.max(0.05, Math.min(0.5, explorationRate + rng.nextGaussian() * 0.02));
        generation++;
    }

    /** Apply a bounded gene delta (from LLM mentoring). Gene names: w0–w4, explorationRate. */
    public void applyDelta(String gene, double delta) {
        delta = Math.max(-0.15, Math.min(0.15, delta));
        switch (gene) {
            case "w0" -> topicWeights[0] = Math.max(0.05, Math.min(1.0, topicWeights[0] + delta));
            case "w1" -> topicWeights[1] = Math.max(0.05, Math.min(1.0, topicWeights[1] + delta));
            case "w2" -> topicWeights[2] = Math.max(0.05, Math.min(1.0, topicWeights[2] + delta));
            case "w3" -> topicWeights[3] = Math.max(0.05, Math.min(1.0, topicWeights[3] + delta));
            case "w4" -> topicWeights[4] = Math.max(0.05, Math.min(1.0, topicWeights[4] + delta));
            case "explorationRate" -> explorationRate = Math.max(0.05, Math.min(0.5, explorationRate + delta));
        }
        generation++;
    }

    /** Weighted random topic selection (w0–w4 as probability mass). */
    public int selectTopic(Random rng) {
        double total = Arrays.stream(topicWeights).sum();
        double r     = rng.nextDouble() * total;
        double cum   = 0;
        for (int i = 0; i < topicWeights.length; i++) {
            cum += topicWeights[i];
            if (r <= cum) return i;
        }
        return topicWeights.length - 1;
    }

    private static double[] clampWeights(double[] w) {
        for (int i = 0; i < w.length; i++) w[i] = Math.max(0.05, Math.min(1.0, w[i]));
        return w;
    }

    // --- Getters / setters for Jackson ---

    public String getDomain()          { return domain; }
    public void   setDomain(String d)  { this.domain = d; }

    public String getMethodology()           { return methodology; }
    public void   setMethodology(String m)   { this.methodology = m; }

    public double[] getTopicWeights()           { return topicWeights; }
    public void     setTopicWeights(double[] w) { this.topicWeights = clampWeights(w.clone()); }

    public double getExplorationRate()          { return explorationRate; }
    public void   setExplorationRate(double e)  { this.explorationRate = Math.max(0.05, Math.min(0.5, e)); }

    public int  getGeneration()         { return generation; }
    public void setGeneration(int g)    { this.generation = g; }
}
