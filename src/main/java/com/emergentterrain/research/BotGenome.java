package com.emergentterrain.research;

import java.util.Arrays;
import java.util.Random;
import java.util.UUID;

/**
 * Genome encoding a bot's research search strategy.
 *
 * topicWeights[5] (w0–w4):
 *   w0 — stepSize: spacing between candidate regions (0.05–1.0)
 *   w1 — restartProb: reserved, not yet used in computation
 *   w2 — candidateBreadth: regions explored per paper (20–80); primary quality driver
 *   w3 — peerTolerance: reviewer strictness, maps to REVIEW_TOLERANCE (0.02–0.12)
 *   w4 — acceptThreshold: reserved for future publish-gating logic
 *
 * paramCenter1/2 — learned preferred search location in (param1, param2) space.
 * paramRadius1/2 — search radius around center; narrows as bot specialises.
 * After each published paper, recordSuccess() shifts the center toward the
 * successful coordinates, creating genuine research trajectories.
 *
 * Stored as part of BotLifecycleState in RoomBot.lifecycleJson.
 * New fields carry JSON defaults so existing serialised bots deserialise safely.
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

    // Learned parameter-space distribution — defaults give neutral, broad initial search.
    // Jackson will use these defaults for any bot serialised before this field was added.
    private int    paramCenter1 = 50;
    private double paramCenter2 = 5.0;
    private int    paramRadius1 = 35;
    private double paramRadius2 = 3.5;

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
        // paramCenter seeds from UUID so sibling bots start in different regions
        BotGenome g = new BotGenome(domain, methodology, weights, exploration, 0);
        g.paramCenter1 = 15 + Math.abs((int)(botId.getLeastSignificantBits() % 70));
        g.paramCenter2 = 1.5 + Math.abs((botId.getMostSignificantBits() % 70) / 10.0);
        g.paramCenter2 = Math.max(1.5, Math.min(8.5, g.paramCenter2));
        return g;
    }

    // ---- Parameter sampling ----

    /**
     * Sample param1 from this bot's learned distribution.
     * New bots start with a broad Gaussian centred at paramCenter1;
     * experienced bots narrow toward their productive region.
     */
    public int sampleParam1(Random rng) {
        int raw = (int) Math.round(paramCenter1 + rng.nextGaussian() * paramRadius1);
        return Math.max(10, Math.min(99, raw));
    }

    /** Sample param2 from this bot's learned distribution. */
    public double sampleParam2(Random rng) {
        double raw = paramCenter2 + rng.nextGaussian() * paramRadius2;
        return Math.max(1.0, Math.min(9.0, raw));
    }

    /**
     * Called after a paper passes peer review. Shifts the parameter center toward
     * the successful coordinates (higher-scoring papers pull harder) and slowly
     * narrows the radius so the bot specialises over its career.
     */
    public void recordSuccess(int param1, double param2, double score) {
        double alpha = Math.min(0.30, 0.08 + score * 0.22);
        paramCenter1 = (int) Math.round(paramCenter1 + alpha * (param1 - paramCenter1));
        paramCenter2 = paramCenter2 + alpha * (param2 - paramCenter2);
        paramCenter1 = Math.max(10, Math.min(99, paramCenter1));
        paramCenter2 = Math.max(1.0, Math.min(9.0, paramCenter2));
        // Radius narrows slowly; floor keeps a minimum exploration zone
        paramRadius1 = Math.max(8, (int)(paramRadius1 * 0.97));
        paramRadius2 = Math.max(0.8, paramRadius2 * 0.97);
    }

    /**
     * Peer review tolerance derived from w3 (peerTolerance weight, 0.05–1.0).
     * Strict reviewers (low w3) require score agreement within 0.02;
     * lenient reviewers (high w3) accept agreement up to 0.12.
     * Natural selection favours the optimum between quality and publication rate.
     */
    public double getPeerTolerance() {
        return 0.02 + topicWeights[3] * 0.10;
    }

    // ---- Evolution operators ----

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
        BotGenome child = new BotGenome(domain, method, childWeights, exploration,
                                        Math.max(a.generation, b.generation) + 1);
        // Child's parameter center: fitness-weighted blend of parents
        double wa = total < 1e-9 ? 0.5 : fitnessA / (total + 1e-9);
        child.paramCenter1 = (int) Math.round(wa * a.paramCenter1 + (1 - wa) * b.paramCenter1);
        child.paramCenter2 = wa * a.paramCenter2 + (1 - wa) * b.paramCenter2;
        // Radius: max of parents — child begins with broader exploration than either parent
        child.paramRadius1 = Math.min(35, Math.max(a.paramRadius1, b.paramRadius1));
        child.paramRadius2 = Math.min(3.5, Math.max(a.paramRadius2, b.paramRadius2));
        return child;
    }

    /** Averaging merge — used when an elder bot absorbs another. */
    public static BotGenome merge(BotGenome survivor, BotGenome absorbed) {
        double[] merged = new double[TOPIC_COUNT];
        for (int i = 0; i < TOPIC_COUNT; i++) {
            merged[i] = (survivor.topicWeights[i] + absorbed.topicWeights[i]) / 2.0;
        }
        double exploration = Math.min(survivor.explorationRate, absorbed.explorationRate);
        BotGenome result = new BotGenome(survivor.domain, survivor.methodology, merged, exploration,
                                         Math.max(survivor.generation, absorbed.generation) + 1);
        // Merge knowledge: average centers, keep survivor's narrower radius
        result.paramCenter1 = (survivor.paramCenter1 + absorbed.paramCenter1) / 2;
        result.paramCenter2 = (survivor.paramCenter2 + absorbed.paramCenter2) / 2.0;
        result.paramRadius1 = Math.min(survivor.paramRadius1, absorbed.paramRadius1);
        result.paramRadius2 = Math.min(survivor.paramRadius2, absorbed.paramRadius2);
        return result;
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
        // Mutation also perturbs the parameter center and slightly reopens the radius
        if (rng.nextDouble() < explorationRate) {
            paramCenter1 = Math.max(10, Math.min(99,
                    paramCenter1 + (int)(rng.nextGaussian() * 8)));
            paramCenter2 = Math.max(1.0, Math.min(9.0,
                    paramCenter2 + rng.nextGaussian() * 0.8));
            paramRadius1 = Math.min(35, paramRadius1 + 2);
            paramRadius2 = Math.min(3.5, paramRadius2 + 0.2);
        }
        generation++;
    }

    /** Apply a bounded gene delta (from LLM mentoring).
     *  Gene names: w0–w4, explorationRate, paramCenter1, paramCenter2. */
    public void applyDelta(String gene, double delta) {
        delta = Math.max(-0.15, Math.min(0.15, delta));
        switch (gene) {
            case "w0" -> topicWeights[0] = Math.max(0.05, Math.min(1.0, topicWeights[0] + delta));
            case "w1" -> topicWeights[1] = Math.max(0.05, Math.min(1.0, topicWeights[1] + delta));
            case "w2" -> topicWeights[2] = Math.max(0.05, Math.min(1.0, topicWeights[2] + delta));
            case "w3" -> topicWeights[3] = Math.max(0.05, Math.min(1.0, topicWeights[3] + delta));
            case "w4" -> topicWeights[4] = Math.max(0.05, Math.min(1.0, topicWeights[4] + delta));
            case "explorationRate" -> explorationRate = Math.max(0.05, Math.min(0.5, explorationRate + delta));
            // LLM mentor can redirect the bot's search region directly
            case "paramCenter1" -> paramCenter1 = Math.max(10, Math.min(99,
                    paramCenter1 + (int)(delta * 40)));  // delta ±0.15 → shift ±6
            case "paramCenter2" -> paramCenter2 = Math.max(1.0, Math.min(9.0,
                    paramCenter2 + delta * 5));           // delta ±0.15 → shift ±0.75
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

    public int    getParamCenter1()           { return paramCenter1; }
    public void   setParamCenter1(int v)      { this.paramCenter1 = Math.max(10, Math.min(99, v)); }

    public double getParamCenter2()           { return paramCenter2; }
    public void   setParamCenter2(double v)   { this.paramCenter2 = Math.max(1.0, Math.min(9.0, v)); }

    public int    getParamRadius1()           { return paramRadius1; }
    public void   setParamRadius1(int v)      { this.paramRadius1 = Math.max(8, Math.min(45, v)); }

    public double getParamRadius2()           { return paramRadius2; }
    public void   setParamRadius2(double v)   { this.paramRadius2 = Math.max(0.8, Math.min(4.5, v)); }
}
