package com.emergentterrain.research.question;

import com.emergentterrain.research.PreRegistration;

/**
 * Allocation-time duplicate screen for work that was selected to answer an open question from a
 * prior paper. A question carries the source paper's locked protocol fingerprint; only an
 * identical fingerprint in the same family is declined. This leaves a changed holdout, window,
 * or other preregistered follow-up eligible for investigation.
 */
public final class QuestionServedProtocolCloneGuard {

    private QuestionServedProtocolCloneGuard() {}

    /** Stable exact fingerprint for a machine-checkable pre-registration, or {@code null}. */
    public static String fingerprint(PreRegistration registration) {
        if (registration == null || registration.mappingNotes() == null) {
            return null;
        }
        String mappingNotes = registration.mappingNotes().replaceAll("\\s+", "");
        return mappingNotes.isBlank() ? null : mappingNotes;
    }

    /** True only for a served question whose source and candidate lock the same protocol. */
    public static boolean isClone(PaperOpenQuestion question, String candidateFamily,
                                  PreRegistration candidateRegistration) {
        if (question == null || candidateFamily == null || !candidateFamily.equals(question.family())) {
            return false;
        }
        String sourceFingerprint = question.protocolFingerprint();
        String candidateFingerprint = fingerprint(candidateRegistration);
        return sourceFingerprint != null && candidateFingerprint != null
                && sourceFingerprint.equals(candidateFingerprint);
    }
}
