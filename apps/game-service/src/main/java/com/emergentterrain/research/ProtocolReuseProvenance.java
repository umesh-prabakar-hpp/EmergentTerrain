package com.emergentterrain.research;

import java.util.Objects;
import java.util.List;

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
        boolean protocolChanged,
        List<String> requestedRemediations) {

    public ProtocolReuseProvenance(String sourcePaperId, String sourceProtocolFingerprint,
                                   Reason reason, boolean protocolChanged) {
        this(sourcePaperId, sourceProtocolFingerprint, reason, protocolChanged, List.of());
    }

    public ProtocolReuseProvenance {
        if (sourcePaperId == null || sourcePaperId.isBlank()) {
            throw new IllegalArgumentException("sourcePaperId is required for protocol reuse");
        }
        if (sourceProtocolFingerprint == null || sourceProtocolFingerprint.isBlank()) {
            throw new IllegalArgumentException("sourceProtocolFingerprint is required for protocol reuse");
        }
        reason = Objects.requireNonNull(reason, "reason");
        requestedRemediations = requestedRemediations == null ? List.of() : requestedRemediations.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip)
                .map(value -> value.length() <= 400 ? value : value.substring(0, 400))
                .limit(5)
                .toList();
        if (reason == Reason.REVIEWER_REMEDIATION_REQUESTED && requestedRemediations.isEmpty()) {
            throw new IllegalArgumentException("reviewer remediation requires retained requests");
        }
    }

    /** Only reasons established by the allocation path are recorded in v1. */
    public enum Reason {
        OPEN_QUESTION_CONTINUATION,
        /** The source panel retained actionable feedback; this records a request, not a resolution. */
        REVIEWER_REMEDIATION_REQUESTED,
        UNSETTLED_IDENTICAL_PROTOCOL
    }
}
