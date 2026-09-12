package com.emergentterrain.research;

import java.util.Objects;

/**
 * Locked provenance for an ordinary reuse of an exact research protocol.
 *
 * <p>This is evidence for later quality assessment, not an instruction to promote, narrate a
 * novelty claim, or alter the selected computation.</p>
 */
public record ProtocolReuseProvenance(
        String sourcePaperId,
        String sourceProtocolFingerprint,
        Reason reason,
        boolean protocolChanged) {

    public ProtocolReuseProvenance {
        if (sourcePaperId == null || sourcePaperId.isBlank()) {
            throw new IllegalArgumentException("sourcePaperId is required for protocol reuse");
        }
        if (sourceProtocolFingerprint == null || sourceProtocolFingerprint.isBlank()) {
            throw new IllegalArgumentException("sourceProtocolFingerprint is required for protocol reuse");
        }
        reason = Objects.requireNonNull(reason, "reason");
    }

    /** Only reasons established by the allocation path are recorded in v1. */
    public enum Reason {
        OPEN_QUESTION_CONTINUATION,
        /** The source panel retained actionable feedback; this records a request, not a resolution. */
        REVIEWER_REMEDIATION_REQUESTED,
        UNSETTLED_IDENTICAL_PROTOCOL
    }
}
