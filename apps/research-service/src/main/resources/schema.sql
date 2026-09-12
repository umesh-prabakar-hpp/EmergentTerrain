CREATE TABLE IF NOT EXISTS research_bot_replicas (
    bot_id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    bot_type VARCHAR(255),
    lifecycle_json TEXT,
    game_tick BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

-- Upgrade Phase 42's initial narrow columns without touching game-owned tables.
ALTER TABLE research_bot_replicas
    ALTER COLUMN lifecycle_json TYPE TEXT;

CREATE TABLE IF NOT EXISTS research_bot_states (
    bot_id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    bot_type VARCHAR(255),
    lifecycle_json TEXT,
    last_source_game_tick BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

ALTER TABLE research_bot_states
    ALTER COLUMN lifecycle_json TYPE TEXT;

CREATE TABLE IF NOT EXISTS research_service_checkpoints (
    room_id UUID PRIMARY KEY,
    last_tick BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS research_lifecycle_shadow_records (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    game_tick BIGINT NOT NULL,
    compared_bots INTEGER NOT NULL,
    matching_bots INTEGER NOT NULL,
    mismatching_bots INTEGER NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS research_lifecycle_projections (
    bot_id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    lifecycle_json TEXT,
    last_observed_game_tick BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS research_lifecycle_projections_room_idx
    ON research_lifecycle_projections (room_id);

ALTER TABLE research_lifecycle_projections
    ADD COLUMN IF NOT EXISTS last_execution_sequence BIGINT NOT NULL DEFAULT 0;

ALTER TABLE research_lifecycle_projections
    ADD COLUMN IF NOT EXISTS execution_epoch VARCHAR(36);

CREATE INDEX IF NOT EXISTS research_bot_replicas_room_idx ON research_bot_replicas (room_id);
CREATE INDEX IF NOT EXISTS research_bot_states_room_idx ON research_bot_states (room_id);
CREATE INDEX IF NOT EXISTS research_lifecycle_shadow_records_room_observed_idx
    ON research_lifecycle_shadow_records (room_id, observed_at DESC);

-- Default-off, research-owned evidence for the game context's completed-consumption window.
CREATE TABLE IF NOT EXISTS research_conjecture_consumption_audit_cursors (
    room_id UUID PRIMARY KEY,
    epoch VARCHAR(255) NOT NULL,
    latest_sequence BIGINT NOT NULL,
    last_outcome VARCHAR(64) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS research_conjecture_consumption_audit_records (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    epoch VARCHAR(255) NOT NULL,
    first_sequence BIGINT NOT NULL,
    latest_sequence BIGINT NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    source_event_id UUID,
    game_tick BIGINT,
    author_bot_id VARCHAR(255),
    domain VARCHAR(255),
    source_id VARCHAR(255),
    selection_kind VARCHAR(64),
    source_observed_at TIMESTAMP WITH TIME ZONE,
    recorded_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS research_conjecture_consumption_audit_records_room_observed_idx
    ON research_conjecture_consumption_audit_records (room_id, recorded_at DESC);

-- Generic durable work intake. These identifiers and payloads deliberately have no game schema.
CREATE TABLE IF NOT EXISTS research_work_items (
    request_id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    work_type VARCHAR(64) NOT NULL,
    source_revision BIGINT NOT NULL,
    request_json TEXT NOT NULL,
    state VARCHAR(32) NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    claimed_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    result_published_at TIMESTAMP WITH TIME ZONE,
    result_json TEXT,
    failure_detail TEXT
);

ALTER TABLE research_work_items ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE research_work_items ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE research_work_items ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE research_work_items ADD COLUMN IF NOT EXISTS result_published_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE research_work_items ADD COLUMN IF NOT EXISTS result_delivery_claimed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE research_work_items ADD COLUMN IF NOT EXISTS result_delivery_rejected_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE research_work_items ADD COLUMN IF NOT EXISTS result_delivery_rejection VARCHAR(64);
ALTER TABLE research_work_items ADD COLUMN IF NOT EXISTS result_json TEXT;
ALTER TABLE research_work_items ADD COLUMN IF NOT EXISTS failure_detail TEXT;

CREATE INDEX IF NOT EXISTS research_work_items_state_accepted_idx
    ON research_work_items (state, accepted_at);

CREATE TABLE IF NOT EXISTS research_draft_plans (
    draft_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    workspace_id UUID NOT NULL,
    subject_id UUID NOT NULL,
    domain VARCHAR(128) NOT NULL,
    topic VARCHAR(512) NOT NULL,
    eligibility VARCHAR(32) NOT NULL,
    selector_version VARCHAR(128) NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    execution_kind VARCHAR(64) NOT NULL,
    execution_payload_json TEXT NOT NULL,
    planned_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE research_draft_plans ADD COLUMN IF NOT EXISTS selector_version VARCHAR(128) NOT NULL DEFAULT 'legacy-unversioned';
ALTER TABLE research_draft_plans ADD COLUMN IF NOT EXISTS input_fingerprint VARCHAR(64) NOT NULL DEFAULT 'unavailable';
ALTER TABLE research_draft_plans ADD COLUMN IF NOT EXISTS execution_kind VARCHAR(64) NOT NULL DEFAULT 'STANDARD_CATALOG';
ALTER TABLE research_draft_plans ADD COLUMN IF NOT EXISTS execution_payload_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE research_draft_plans ADD COLUMN IF NOT EXISTS fallback_parameter_sample_json TEXT;
ALTER TABLE research_draft_plans ADD COLUMN IF NOT EXISTS formulation_specification_json TEXT;
ALTER TABLE research_draft_plans ADD COLUMN IF NOT EXISTS computation_specification_json TEXT;

-- Frozen execution-readiness records are research-owned and intentionally do not represent a
-- computation result. They are the durable input boundary for a later reviewed executor.
CREATE TABLE IF NOT EXISTS research_execution_preparations (
    preparation_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL UNIQUE,
    request_id UUID NOT NULL UNIQUE,
    preparation_version VARCHAR(128) NOT NULL,
    execution_kind VARCHAR(64) NOT NULL,
    readiness VARCHAR(64) NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    canonical_input_json TEXT NOT NULL,
    prepared_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Controlled sidecar observations are research-owned parity evidence, never papers or game effects.
CREATE TABLE IF NOT EXISTS research_computation_audits (
    audit_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL UNIQUE,
    request_id UUID NOT NULL UNIQUE,
    audit_version VARCHAR(128) NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    observed_output_json TEXT NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Direct sidecar observations are separate from legacy-delegate shadows so their raw outputs can
-- be compared without granting an execution result any paper or game authority.
CREATE TABLE IF NOT EXISTS research_computation_runs (
    run_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL UNIQUE,
    request_id UUID NOT NULL UNIQUE,
    runner_version VARCHAR(128) NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    observed_output_json TEXT NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- A parity record compares the shared numeric envelope of one legacy shadow and one direct run.
-- It remains research-owned evidence and never authorizes a result, claim, publication, or effect.
CREATE TABLE IF NOT EXISTS research_computation_parities (
    comparison_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL UNIQUE,
    request_id UUID NOT NULL UNIQUE,
    comparison_version VARCHAR(128) NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    compared_envelope_json TEXT NOT NULL,
    compared_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Controlled failure comparisons retain canonical categories only, then let normal work retry semantics proceed.
CREATE TABLE IF NOT EXISTS research_computation_failure_parities (
    comparison_id UUID PRIMARY KEY,
    draft_id UUID NOT NULL UNIQUE,
    request_id UUID NOT NULL UNIQUE,
    comparison_version VARCHAR(128) NOT NULL,
    input_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    legacy_outcome VARCHAR(64) NOT NULL,
    direct_outcome VARCHAR(64) NOT NULL,
    compared_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Phase 43: research-owned, append-only AI quality adjudications. This is deliberately separate
-- from the legacy game-owned research_papers workflow row: an assessment does not rewrite a
-- paper's status, computation result, narration, or game state.
CREATE TABLE IF NOT EXISTS research_paper_quality_assessments (
    assessment_id UUID PRIMARY KEY,
    paper_id VARCHAR(128) NOT NULL,
    assessment_revision INTEGER NOT NULL,
    rubric_version VARCHAR(128) NOT NULL,
    assessor_model VARCHAR(256) NOT NULL,
    grade VARCHAR(64) NOT NULL,
    rationale TEXT NOT NULL,
    evidence_references_json TEXT NOT NULL,
    unmet_requirements_json TEXT NOT NULL,
    contradictions_json TEXT NOT NULL,
    reviewer_summary TEXT,
    replication_summary TEXT,
    benchmark_summary TEXT,
    assessed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_research_paper_quality_assessment_revision
        UNIQUE (paper_id, assessment_revision)
);

CREATE INDEX IF NOT EXISTS research_paper_quality_assessment_paper_revision_idx
    ON research_paper_quality_assessments (paper_id, assessment_revision DESC);
