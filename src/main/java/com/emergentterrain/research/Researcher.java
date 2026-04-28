package com.emergentterrain.research;

import java.util.Arrays;
import java.util.Random;

/**
 * A bot assigned to university research. Carries a genome encoding domain focus,
 * topic weights, and methodology. Fitness-based selection drives genetic evolution.
 */
public class Researcher {

    public static final String[] DOMAINS      = {"MATHEMATICS", "PHYSICS", "MATERIALS"};
    public static final String[] METHODOLOGIES = {"THEORETICAL", "COMPUTATIONAL", "EMPIRICAL"};
    private static final int TOPIC_COUNT = 5;

    private final String botId;
    private final String domain;
    private String methodology;
    private double[] topicWeights; // probability mass over 5 sub-topics within domain
    private double explorationRate; // mutation rate / exploration vs exploitation
    private int generation;
    private int publishedCount;
    private int rejectedCount;
    private double reputation; // 0–1, grows with successful publications

    public Researcher(String botId, String domain, Random rng) {
        this.botId = botId;
        this.domain = domain;
        this.methodology = METHODOLOGIES[rng.nextInt(METHODOLOGIES.length)];
        this.topicWeights = new double[TOPIC_COUNT];
        for (int i = 0; i < TOPIC_COUNT; i++) topicWeights[i] = 0.1 + rng.nextDouble() * 0.8;
        this.explorationRate = 0.1 + rng.nextDouble() * 0.3;
        this.generation = 0;
        this.reputation = 0.2;
    }

    private Researcher(String botId, String domain, String methodology,
                       double[] topicWeights, double explorationRate, int generation) {
        this.botId = botId;
        this.domain = domain;
        this.methodology = methodology;
        this.topicWeights = topicWeights.clone();
        this.explorationRate = explorationRate;
        this.generation = generation;
        this.reputation = 0.1;
    }

    /** Weighted random topic selection based on genome. */
    public int selectTopic(Random rng) {
        double total = Arrays.stream(topicWeights).sum();
        double r = rng.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < topicWeights.length; i++) {
            cumulative += topicWeights[i];
            if (r <= cumulative) return i;
        }
        return topicWeights.length - 1;
    }

    /** Less reputable bots make more computational errors. */
    public double errorRate() {
        return Math.max(0.05, 0.4 - reputation * 0.35);
    }

    public void recordPublished(double score) {
        publishedCount++;
        reputation = Math.min(1.0, reputation + 0.05 * score);
    }

    public void recordRejected() {
        rejectedCount++;
        reputation = Math.max(0.0, reputation - 0.02);
    }

    /** Fitness = weighted publication rate, used for tournament selection. */
    public double fitness() {
        if (publishedCount + rejectedCount == 0) return 0.1;
        return (publishedCount * reputation) / (publishedCount + rejectedCount + 1.0);
    }

    /** Gaussian mutation of topic weights and exploration rate. */
    public void mutate(Random rng) {
        for (int i = 0; i < topicWeights.length; i++) {
            if (rng.nextDouble() < explorationRate) {
                topicWeights[i] = Math.max(0.05, Math.min(1.0,
                        topicWeights[i] + rng.nextGaussian() * 0.15));
            }
        }
        explorationRate = Math.max(0.05, Math.min(0.5,
                explorationRate + rng.nextGaussian() * 0.02));
        generation++;
    }

    /** Single-point crossover producing a child genome. */
    public static Researcher crossover(Researcher a, Researcher b, String childBotId, Random rng) {
        int point = 1 + rng.nextInt(TOPIC_COUNT - 1);
        double[] childWeights = new double[TOPIC_COUNT];
        System.arraycopy(a.topicWeights, 0, childWeights, 0, point);
        System.arraycopy(b.topicWeights, point, childWeights, point, TOPIC_COUNT - point);
        String method = rng.nextBoolean() ? a.methodology : b.methodology;
        double exploration = (a.explorationRate + b.explorationRate) / 2.0;
        double totalFitness = a.fitness() + b.fitness();
        String domain = (totalFitness < 1e-9 || rng.nextDouble() < a.fitness() / (totalFitness + 1e-9))
                ? a.domain : b.domain;
        return new Researcher(childBotId, domain, method, childWeights, exploration,
                Math.max(a.generation, b.generation) + 1);
    }

    public String getBotId()          { return botId; }
    public String getDomain()         { return domain; }
    public String getMethodology()    { return methodology; }
    public double[] getTopicWeights() { return topicWeights; }
    public double getExplorationRate(){ return explorationRate; }
    public int getGeneration()        { return generation; }
    public int getPublishedCount()    { return publishedCount; }
    public int getRejectedCount()     { return rejectedCount; }
    public double getReputation()     { return reputation; }
}
