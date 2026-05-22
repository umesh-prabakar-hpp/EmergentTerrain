package com.emergentterrain.research.recognise;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Phase 26 §26.1 — Postgres-backed cache row for a single OEIS
 * Superseeker lookup. Keyed on the SHA-256 hash of the canonical
 * (comma-joined) sequence string so the same sequence always maps to
 * the same row regardless of int-array equality semantics.
 *
 * <h2>Why a cache</h2>
 *
 * OEIS asks for ≤1 req/sec; corpus-typical workloads rediscover the
 * same handful of sequences (Fibonacci, primes, partitions, powers
 * of two) thousands of times. The cache turns those into
 * sub-millisecond lookups so the live HTTP only fires on a genuinely
 * new sequence.
 *
 * <h2>Hit vs miss persistence</h2>
 *
 * The {@link #hit} field distinguishes "OEIS knows this sequence" (true,
 * with oeisId/name/transformChain populated) from "OEIS doesn't know
 * this sequence" (false, with all other fields null). Caching misses
 * is intentional — repeated lookups of an unknown sequence still
 * pay zero outbound HTTP cost. The cache row has no TTL in v1; if
 * OEIS adds a new sequence that matches an existing cached miss,
 * the operator should clear the row manually.
 */
@Entity
@Table(name = "oeis_cache")
public class OeisCacheEntry {

    /**
     * SHA-256 hex of the canonical comma-joined sequence
     * ("1,1,2,3,5,8,13" → SHA-256). 64-char hex string.
     */
    @Id
    @Column(name = "sequence_hash", length = 64, nullable = false)
    private String sequenceHash;

    @Column(name = "oeis_id", length = 16)
    private String oeisId;

    @Column(name = "name", length = 4096)
    private String name;

    @Column(name = "transform_chain", length = 256)
    private String transformChain;

    @Column(name = "hit", nullable = false)
    private boolean hit;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    /** JPA needs a no-arg constructor. */
    protected OeisCacheEntry() {}

    public OeisCacheEntry(String sequenceHash,
                           String oeisId,
                           String name,
                           String transformChain,
                           boolean hit,
                           Instant cachedAt) {
        this.sequenceHash = sequenceHash;
        this.oeisId = oeisId;
        this.name = name;
        this.transformChain = transformChain;
        this.hit = hit;
        this.cachedAt = cachedAt;
    }

    public String sequenceHash() { return sequenceHash; }
    public String oeisId()        { return oeisId; }
    public String name()          { return name; }
    public String transformChain(){ return transformChain; }
    public boolean hit()          { return hit; }
    public Instant cachedAt()     { return cachedAt; }
}
