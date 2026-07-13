package com.emergentterrain.research.publication;

/**
 * Locked before computation begins.  It separates exploratory model selection
 * from the fixed evaluation that may be described as confirmatory in a
 * computational paper.  This is deliberately domain-neutral: a scope can be
 * an integer interval, simulation seed set, public dataset split, or parameter
 * grid.
 */
public record ComputationalStudyPlan(
        String discoveryScope,
        String confirmationScope,
        String selectionRule,
        String sensitivityPlan,
        String reproductionCommand
) {
    public boolean hasSelectionSeparation() {
        return present(discoveryScope)
                && present(confirmationScope)
                && !normalize(discoveryScope).equals(normalize(confirmationScope))
                && present(selectionRule);
    }

    public boolean hasReproductionCommand() {
        return present(reproductionCommand);
    }

    public boolean hasSensitivityPlan() {
        return present(sensitivityPlan);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }
}
