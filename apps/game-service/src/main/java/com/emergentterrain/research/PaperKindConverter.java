package com.emergentterrain.research;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 2026-05-29 — maps {@link PaperKind} to its String name via a converter instead of
 * {@code @Enumerated(EnumType.STRING)}, specifically so Hibernate does NOT auto-generate
 * a {@code check (kind in (...))} constraint.
 *
 * <p>Those @Enumerated-generated CHECK constraints can't be maintained by
 * {@code ddl-auto: update} when the enum gains a value — they silently blocked inserts on
 * the long-lived DB (CONJECTURE_INVESTIGATION rejected by a stale {@code DOMAIN,META} check).
 * The Java enum is the source of truth for valid values, so the DB-level check is redundant.
 * The stored value is identical to the former {@code @Enumerated(STRING)} mapping
 * ({@code e.name()}), so existing rows read back unchanged.
 */
@Converter
public class PaperKindConverter implements AttributeConverter<PaperKind, String> {
    @Override
    public String convertToDatabaseColumn(PaperKind value) {
        return value == null ? null : value.name();
    }

    @Override
    public PaperKind convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : PaperKind.valueOf(dbValue);
    }
}
