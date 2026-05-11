package com.emergentterrain.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Phase 4b — closes the meta-science loop on two of the simulation's exploration
 * knobs:
 *
 * <ul>
 *   <li><b>Replication z-threshold</b> ({@link ReplicationService#ANOMALY_REPLICATION_Z}):
 *       lower values queue more replications. Tracked per room in {@link #zThresholdByRoom}.
 *   <li><b>Exploration trigger uncertainty</b> ({@link ExperimentPlannerService}'s
 *       constant): higher values require greater confidence before a research
 *       program escapes its genome region. Tracked per room in {@link #explorationTriggerByRoom}.
 * </ul>
 *
 * <p>Control loop: every {@link #ADJUST_EVERY_N_REPLICATIONS} processed replications
 * in a room, read {@link ReplicationService#recentFailureRate}:
 *
 * <ul>
 *   <li>If failure rate &lt; {@link #LOW_FAILURE_FLOOR} (replicating too eagerly —
 *       most replications confirm), raise both thresholds by {@link #STEP_SIZE}.
 *   <li>If failure rate &gt; {@link #HIGH_FAILURE_CEILING} (catching real anomalies —
 *       we may be missing more), lower both by {@link #STEP_SIZE}.
 * </ul>
 *
 * <p>Both thresholds are clamped to safe ranges so the loop cannot run away. The
 * Ramsdell framing — "you're going to be wrong and you're going to not get
 * through this. You have to have a long-term perspective" — informs the choice
 * to tune both knobs together: an aggressive replication policy paired with an
 * eager exploration policy makes the simulation jumpy; conservative pairings
 * make it stubborn.
 *
 * <p>State is in-memory only; on restart, thresholds revert to the static
 * defaults. Same trade-off as FundingAllocationService — intentional and
 * documented.
 *
 * <p>Gated by {@code app.research.exploration-tuning.enabled} (default false).
 * When off, {@link #getZThreshold} returns the static default and
 * {@link #getExplorationTrigger} returns null (caller's static constant wins).
 */
@Service
public class ExplorationTuningService {

    private static final Logger log = LoggerFactory.getLogger(ExplorationTuningService.class);

    static final double DEFAULT_Z_THRESHOLD            = 3.0;
    static final double DEFAULT_EXPLORATION_TRIGGER    = 0.25;
    static final double Z_MIN                          = 1.5;
    static final double Z_MAX                          = 5.0;
    static final double EXPLORATION_TRIGGER_MIN        = 0.10;
    static final double EXPLORATION_TRIGGER_MAX        = 0.50;
    static final double LOW_FAILURE_FLOOR              = 0.10;
    static final double HIGH_FAILURE_CEILING           = 0.40;
    static final double STEP_SIZE_Z                    = 0.25;
    static final double STEP_SIZE_EXPLORATION          = 0.05;
    static final int    ADJUST_EVERY_N_REPLICATIONS    = 10;

    private final boolean enabled;
    private final ReplicationService replications;

    private final ConcurrentMap<UUID, Double>  zThresholdByRoom          = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Double>  explorationTriggerByRoom  = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> sinceLastAdjustByRoom     = new ConcurrentHashMap<>();

    public ExplorationTuningService(
            @Value("${app.research.exploration-tuning.enabled:false}") boolean enabled,
            ReplicationService replications) {
        this.enabled      = enabled;
        this.replications = replications;
        if (enabled) {
            log.info("ExplorationTuningService: ENABLED — z-threshold and exploration-trigger "
                    + "adjust per-room every {} replications, driven by recentFailureRate. "
                    + "Bounds: z=[{},{}], exploration=[{},{}].",
                    ADJUST_EVERY_N_REPLICATIONS, Z_MIN, Z_MAX,
                    EXPLORATION_TRIGGER_MIN, EXPLORATION_TRIGGER_MAX);
        }
    }

    /** Read by {@link UniversityService#publishPaper}. Returns the static default when disabled or untuned. */
    public double getZThreshold(UUID roomId) {
        if (!enabled || roomId == null) return DEFAULT_Z_THRESHOLD;
        return zThresholdByRoom.getOrDefault(roomId, DEFAULT_Z_THRESHOLD);
    }

    /** Read by {@link ExperimentPlannerService}. Returns the static default when disabled or untuned. */
    public double getExplorationTrigger(UUID roomId) {
        if (!enabled || roomId == null) return DEFAULT_EXPLORATION_TRIGGER;
        return explorationTriggerByRoom.getOrDefault(roomId, DEFAULT_EXPLORATION_TRIGGER);
    }

    /**
     * Called by {@link UniversityService} after each {@link ReplicationService#processOne}
     * that actually processed a request. Bumps the per-room counter and runs the
     * adjustment when the counter crosses {@link #ADJUST_EVERY_N_REPLICATIONS}.
     */
    public void onReplicationProcessed(UUID roomId) {
        if (!enabled || roomId == null) return;
        int count = sinceLastAdjustByRoom.merge(roomId, 1, Integer::sum);
        if (count < ADJUST_EVERY_N_REPLICATIONS) return;
        sinceLastAdjustByRoom.put(roomId, 0);
        adjust(roomId);
    }

    private void adjust(UUID roomId) {
        double failureRate = replications.recentFailureRate(roomId);
        if (failureRate < 0) return;  // not enough samples yet
        double oldZ           = getZThreshold(roomId);
        double oldExploration = getExplorationTrigger(roomId);
        double newZ           = oldZ;
        double newExploration = oldExploration;
        if (failureRate < LOW_FAILURE_FLOOR) {
            // Replications mostly confirm — we're flagging too aggressively.
            // Push both knobs toward persistence.
            newZ           = Math.min(Z_MAX,                   oldZ           + STEP_SIZE_Z);
            newExploration = Math.min(EXPLORATION_TRIGGER_MAX, oldExploration + STEP_SIZE_EXPLORATION);
        } else if (failureRate > HIGH_FAILURE_CEILING) {
            // Replications often fail — real anomalies are coming through;
            // probably more we're missing. Push both knobs toward broader search.
            newZ           = Math.max(Z_MIN,                   oldZ           - STEP_SIZE_Z);
            newExploration = Math.max(EXPLORATION_TRIGGER_MIN, oldExploration - STEP_SIZE_EXPLORATION);
        }
        if (newZ != oldZ || newExploration != oldExploration) {
            zThresholdByRoom.put(roomId, newZ);
            explorationTriggerByRoom.put(roomId, newExploration);
            log.info("exploration tuning roomId={} failureRate={} z={}->{} explorationTrigger={}->{}",
                    roomId, String.format("%.2f", failureRate),
                    String.format("%.2f", oldZ), String.format("%.2f", newZ),
                    String.format("%.2f", oldExploration), String.format("%.2f", newExploration));
        }
    }
}
