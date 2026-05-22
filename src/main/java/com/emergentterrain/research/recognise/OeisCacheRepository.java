package com.emergentterrain.research.recognise;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Phase 26 §26.1 — Spring Data repository for {@link OeisCacheEntry}.
 * The id type is {@link String} (the SHA-256 hex of the canonical
 * sequence string); standard {@code findById} / {@code save}
 * semantics give the cache-aside pattern its read + upsert paths.
 */
@Repository
public interface OeisCacheRepository extends JpaRepository<OeisCacheEntry, String> {
}
