package com.emergentterrain.research.conjecture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 30 Wave 5 — 30.5 P3b: a persisted MDL-admitted derived scalar.
 *
 * <p>Stores the admitted scalar's canonical render ({@code name}), depth, and net MDL gain so the
 * grown vocabulary survives restarts. The {@link OperatorGrammar.Expr} itself is NOT serialised — it
 * is reconstructed on load by re-enumerating the (deterministic, non-recursive) grammar and matching
 * by render, which avoids an expression (de)serialiser. A row whose name no longer re-enumerates
 * (e.g. the base vocabulary changed) is simply dropped on load.
 *
 * <p>New table {@code derived_scalars} — purely additive, created by {@code ddl-auto: update}; no
 * enums (so no stale CHECK constraints) and all columns set on every insert.
 */
@Entity
@Table(name = "derived_scalars",
        uniqueConstraints = @UniqueConstraint(columnNames = {"domain", "name"}))
public class DerivedScalarEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String domain;

    /** Canonical render, e.g. {@code "log(prime_density)"} — also the vocabulary key. */
    @Column(nullable = false, length = 512)
    private String name;

    @Column(nullable = false)
    private int depth;

    @Column(nullable = false)
    private double gainBits;

    @Column(nullable = false)
    private Instant admittedAt;

    protected DerivedScalarEntity() {
    }   // JPA

    public DerivedScalarEntity(String domain, String name, int depth, double gainBits, Instant admittedAt) {
        this.domain = domain;
        this.name = name;
        this.depth = depth;
        this.gainBits = gainBits;
        this.admittedAt = admittedAt;
    }

    public UUID id() { return id; }
    public String domain() { return domain; }
    public String name() { return name; }
    public int depth() { return depth; }
    public double gainBits() { return gainBits; }
    public Instant admittedAt() { return admittedAt; }
}
