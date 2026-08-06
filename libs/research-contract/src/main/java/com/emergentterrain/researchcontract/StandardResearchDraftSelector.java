package com.emergentterrain.researchcontract;

import java.util.List;
import java.util.Random;

/** Pure selector for the standard research-draft path; it consumes only frozen generic input. */
public final class StandardResearchDraftSelector {
    private static final int TOURNAMENT_SIZE = 3;

    private StandardResearchDraftSelector() { }

    public static ResearchDraftSelection select(ResearchDraftSelectionInput input) {
        return select(input, new Random(input.selectionSeed()));
    }

    /** Accepts an existing RNG only for byte-compatible migration parity with a legacy caller. */
    public static ResearchDraftSelection select(ResearchDraftSelectionInput input, Random random) {
        ResearchDraftCandidate winner = selectCandidate(input, random);
        ResearchTopicOption topic = selectTopic(input, winner, random);
        boolean hasPrimary = input.candidates().stream().anyMatch(candidate -> "PRIMARY".equals(candidate.tier()));
        String eligibility = hasPrimary ? "CANDIDATE" : "FALLBACK_CANDIDATE";
        return new ResearchDraftSelection(winner.subjectId(), eligibility, winner.domain(), topic.topic());
    }

    /** Selects the candidate without consuming the later topic-selection random draws. */
    public static ResearchDraftCandidate selectCandidate(ResearchDraftSelectionInput input, Random random) {
        List<ResearchDraftCandidate> primary = input.candidates().stream()
                .filter(candidate -> "PRIMARY".equals(candidate.tier())).toList();
        List<ResearchDraftCandidate> pool = primary.isEmpty()
                ? input.candidates().stream().filter(candidate -> "FALLBACK".equals(candidate.tier())).toList()
                : primary;
        if (pool.isEmpty()) throw new IllegalArgumentException("No eligible draft candidate");

        ResearchDraftCandidate winner = null;
        for (int draw = 0; draw < TOURNAMENT_SIZE; draw++) {
            ResearchDraftCandidate candidate = pool.get(random.nextInt(pool.size()));
            if (winner == null || candidate.selectionScore() > winner.selectionScore()) winner = candidate;
        }
        return winner;
    }

    /** Selects a topic after candidate-only branches have had a chance to complete. */
    public static ResearchTopicOption selectTopic(ResearchDraftSelectionInput input, ResearchDraftCandidate winner,
                                                   Random random) {
        // Preserve the legacy draw cadence: its weighted selector runs before the optional uniform selector.
        double weightedRoll = random.nextDouble();
        return "UNIFORM".equals(input.topicSelectionMode())
                ? winner.topicOptions().get(random.nextInt(winner.topicOptions().size()))
                : weighted(winner.topicOptions(), weightedRoll);
    }

    private static ResearchTopicOption weighted(List<ResearchTopicOption> options, double roll) {
        double total = options.stream().mapToDouble(ResearchTopicOption::weight).sum();
        if (total <= 0.0) return options.get(options.size() - 1);
        double target = roll * total;
        double cumulative = 0.0;
        for (ResearchTopicOption option : options) {
            cumulative += option.weight();
            if (target <= cumulative) return option;
        }
        return options.get(options.size() - 1);
    }
}
