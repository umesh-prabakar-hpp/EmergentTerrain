CREATE TABLE IF NOT EXISTS research_bot_replicas (
    bot_id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    bot_type VARCHAR(255),
    lifecycle_json TEXT,
    game_tick BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS research_bot_states (
    bot_id UUID PRIMARY KEY,
    room_id UUID NOT NULL,
    bot_type VARCHAR(255),
    lifecycle_json TEXT,
    last_source_game_tick BIGINT NOT NULL,
    active BOOLEAN NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE
);

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

CREATE INDEX IF NOT EXISTS research_bot_replicas_room_idx ON research_bot_replicas (room_id);
CREATE INDEX IF NOT EXISTS research_bot_states_room_idx ON research_bot_states (room_id);
CREATE INDEX IF NOT EXISTS research_lifecycle_shadow_records_room_observed_idx
    ON research_lifecycle_shadow_records (room_id, observed_at DESC);
