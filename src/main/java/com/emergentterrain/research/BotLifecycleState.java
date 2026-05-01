package com.emergentterrain.research;

/**
 * Per-bot lifecycle and research state, serialised to RoomBot.lifecycleJson.
 * Covers genome, age-phase transitions, competency status, dating/merge timing,
 * and cumulative research record.
 */
public class BotLifecycleState {

    public enum Phase          { JUVENILE, ADULT, ELDER }
    public enum ResearchStatus { UNQUALIFIED, QUALIFIED }

    // Thresholds (game ticks)
    public static final long JUVENILE_TO_ADULT = 100L;
    public static final long ADULT_TO_ELDER    = 800L;

    private BotGenome      genome;
    private long           ageTicks         = 0L;
    private Phase          lifecycle        = Phase.JUVENILE;
    private ResearchStatus researchStatus   = ResearchStatus.UNQUALIFIED;
    private double         lastCompetencyScore = 0.0;
    private long           lastCompetencyTick  = -1L;
    private long           lastDatedAt         = -1L;
    private long           lastAiEditAt        = -1L;
    private int            publishedCount   = 0;
    private int            rejectedCount    = 0;
    private double         reputation       = 0.2;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private DynamicProblem pendingDirective = null;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private FollowUpParams pendingFollowUp  = null;

    // For Jackson
    public BotLifecycleState() {}

    // --- Domain helpers ---

    public void incrementAge() {
        incrementAge(JUVENILE_TO_ADULT, ADULT_TO_ELDER);
    }

    public void incrementAge(long juvenileToAdult, long adultToElder) {
        ageTicks++;
        if (ageTicks >= adultToElder) {
            lifecycle = Phase.ELDER;
        } else if (ageTicks >= juvenileToAdult) {
            lifecycle = Phase.ADULT;
        }
    }

    public double errorRate() {
        return Math.max(0.05, 0.4 - reputation * 0.35);
    }

    public double fitness() {
        if (publishedCount + rejectedCount == 0) return 0.1;
        return (publishedCount * reputation) / (publishedCount + rejectedCount + 1.0);
    }

    public void recordPublished(double score) {
        publishedCount++;
        reputation = Math.min(1.0, reputation + 0.05 * score);
    }

    public void recordRejected() {
        rejectedCount++;
        reputation = Math.max(0.0, reputation - 0.02);
    }

    // --- Getters / setters for Jackson ---

    public BotGenome getGenome()                   { return genome; }
    public void      setGenome(BotGenome g)        { this.genome = g; }

    public long getAgeTicks()                      { return ageTicks; }
    public void setAgeTicks(long a)                { this.ageTicks = a; }

    public Phase getLifecycle()                    { return lifecycle; }
    public void  setLifecycle(Phase l)             { this.lifecycle = l; }

    public ResearchStatus getResearchStatus()             { return researchStatus; }
    public void           setResearchStatus(ResearchStatus s) { this.researchStatus = s; }

    public double getLastCompetencyScore()                { return lastCompetencyScore; }
    public void   setLastCompetencyScore(double s)        { this.lastCompetencyScore = s; }

    public long getLastCompetencyTick()                   { return lastCompetencyTick; }
    public void setLastCompetencyTick(long t)             { this.lastCompetencyTick = t; }

    public long getLastDatedAt()                          { return lastDatedAt; }
    public void setLastDatedAt(long t)                    { this.lastDatedAt = t; }

    public long getLastAiEditAt()                         { return lastAiEditAt; }
    public void setLastAiEditAt(long t)                   { this.lastAiEditAt = t; }

    public int    getPublishedCount()                     { return publishedCount; }
    public void   setPublishedCount(int c)                { this.publishedCount = c; }

    public int    getRejectedCount()                      { return rejectedCount; }
    public void   setRejectedCount(int c)                 { this.rejectedCount = c; }

    public double getReputation()                         { return reputation; }
    public void   setReputation(double r)                 { this.reputation = r; }

    public DynamicProblem getPendingDirective()            { return pendingDirective; }
    public void           setPendingDirective(DynamicProblem d) { this.pendingDirective = d; }

    public FollowUpParams getPendingFollowUp()              { return pendingFollowUp; }
    public void           setPendingFollowUp(FollowUpParams f) { this.pendingFollowUp = f; }
}
