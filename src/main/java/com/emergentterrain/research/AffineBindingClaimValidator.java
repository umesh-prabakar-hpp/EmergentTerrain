package com.emergentterrain.research;

import com.emergentterrain.llm.ToolInvocation;
import com.emergentterrain.research.conjecture.ConjectureInvestigationWriteBackService;

import java.util.List;
import java.util.Locale;

/** Keeps a prose binding-regime claim aligned with the selected affine evidence. */
public final class AffineBindingClaimValidator {
    private AffineBindingClaimValidator() {}

    public static boolean claimWithoutDocumentedPair(String hypothesis, String findings, String conclusion,
                                                      List<ToolInvocation> trace) {
        String prose = String.join("\n", nullToEmpty(hypothesis), nullToEmpty(findings), nullToEmpty(conclusion))
                .toLowerCase(Locale.ROOT);
        if (!prose.contains("binding regime") && !prose.contains("demonstrated binding")) return false;
        List<ConjectureInvestigationWriteBackService.HeldBound> held =
                ConjectureInvestigationWriteBackService.parseHeldBounds(trace);
        for (ConjectureInvestigationWriteBackService.HeldBound bound : held) {
            if (bound.relSlack() != null && bound.relSlack() <= 0.05
                    && ConjectureInvestigationWriteBackService.hasDocumentedBindingPair(bound, trace)) {
                return false;
            }
        }
        return true;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
