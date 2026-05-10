package com.emergentterrain.research;

import com.emergentterrain.domain.RoomBot;
import com.emergentterrain.government.GovernmentState;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Pure-function service that computes per-room population parameters from government
 * state plus the recent peer-score distribution.
 *
 * Carved out of UniversityService in Phase 0 Task 0.3 so the parameter formulas can
 * evolve (e.g. Phase 1 will surface K and lifespan in the per-bot research identity)
 * without churning the main lifecycle orchestrator.
 *
 * Stateless. Safe to inject as a singleton.
 */
@Service
public class PopulationEcologyService {

    public record PopulationParams(int K, long adultToElder, long datingCooldown,
                                   double scoreVariance, double fillRatio, double educationFactor) {}

    /**
     * Compute population dynamics from government state + recent peer-score variance.
     *
     * @param bots         all ACTIVE bots in the room
     * @param govState     current government state (may be null when government is disabled)
     * @param recentPapers recent papers used to estimate score variance (callers typically
     *                     pass the last 20 published papers from UniversityService)
     */
    public PopulationParams computePopulationParams(List<RoomBot> bots,
                                                    GovernmentState govState,
                                                    List<ResearchPaper> recentPapers) {
        double educationFactor = 0.3;
        double healthFactor    = 0.3;
        double trustFactor     = 0.5;
        double serviceFactor   = 0.5;

        if (govState != null && govState.getFederal() != null) {
            GovernmentState.BudgetState budget = govState.getFederal().getBudget();
            if (budget != null) {
                educationFactor = Math.min(1.0, budget.getEducation() / 100_000.0);
                healthFactor    = Math.min(1.0, budget.getHealth()    / 100_000.0);
            }
            trustFactor   = Math.min(1.0, Math.max(0.0, govState.getFederal().getPublicTrust()));
            serviceFactor = Math.min(1.0, Math.max(0.0, govState.getFederal().getServiceAccess()));
        }

        // K: 60 (bare minimum) up to 1200 (thriving society)
        int K = (int)(60 + educationFactor * 600 + trustFactor * 300 + serviceFactor * 240);

        // Adult lifespan: 800 base ticks + health/education investment (800–5800 ticks)
        long adultToElder = (long)(800 + healthFactor * 3000 + educationFactor * 2000);

        // Evolutionary pressure: high score variance → shorter generations (faster exploration)
        double avg = recentPapers.stream().mapToDouble(ResearchPaper::peerScore).average().orElse(0.2);
        double variance = recentPapers.stream()
                .mapToDouble(p -> (p.peerScore() - avg) * (p.peerScore() - avg))
                .average().orElse(0.0);
        double stdDev = Math.sqrt(variance);
        // stdDev near 0.15 is neutral; higher → shorten generations, lower → extend
        adultToElder += (long)((0.15 - stdDev) * 1000);
        adultToElder = Math.max(800, Math.min(6000, adultToElder));

        // Dating cooldown: quadratic braking as population approaches K
        double fillRatio = K > 0 ? Math.min(1.0, (double) bots.size() / K) : 1.0;
        long datingCooldown = (long)(200 + fillRatio * fillRatio * 1800);

        return new PopulationParams(K, adultToElder, datingCooldown, stdDev, fillRatio, educationFactor);
    }
}
