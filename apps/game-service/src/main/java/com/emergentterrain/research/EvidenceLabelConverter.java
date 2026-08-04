package com.emergentterrain.research;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 2026-05-29 — maps {@link EvidenceLabel} to its String name via a converter instead of
 * {@code @Enumerated(EnumType.STRING)}, so Hibernate does NOT auto-generate a
 * {@code check (evidence_label in (...))} constraint.
 *
 * <p>The pre-existing @Enumerated check was stale ({@code THEOREM,EMPIRICAL_RESULT,CONJECTURE,
 * UNEXPLAINED_OBSERVATION} — missing {@code KNOWN_RESULT}) and ddl-auto can't update it, which
 * would block a KNOWN_RESULT-labelled paper from persisting. Null coerces to CONJECTURE (the
 * honest floor), matching {@code ResearchPaper.setEvidenceLabel}. Stored value is identical to
 * the former {@code @Enumerated(STRING)} mapping.
 */
@Converter
public class EvidenceLabelConverter implements AttributeConverter<EvidenceLabel, String> {
    @Override
    public String convertToDatabaseColumn(EvidenceLabel value) {
        return (value == null ? EvidenceLabel.CONJECTURE : value).name();
    }

    @Override
    public EvidenceLabel convertToEntityAttribute(String dbValue) {
        return dbValue == null ? EvidenceLabel.CONJECTURE : EvidenceLabel.valueOf(dbValue);
    }
}
