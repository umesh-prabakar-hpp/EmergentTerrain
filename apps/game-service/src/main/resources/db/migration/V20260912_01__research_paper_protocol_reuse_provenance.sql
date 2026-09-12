-- Game-owned Phase 43 additive migration.
--
-- The research-only service reads this provenance while deciding whether an
-- otherwise identical protocol may be reused. It deliberately runs with
-- Hibernate DDL disabled, so this column must be present before that worker
-- starts.
ALTER TABLE research_papers
    ADD COLUMN IF NOT EXISTS protocol_reuse_provenance TEXT;
