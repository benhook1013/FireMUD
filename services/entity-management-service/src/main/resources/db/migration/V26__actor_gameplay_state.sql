CREATE TABLE actor_resource_states (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    game_instance_id VARCHAR(255) NOT NULL,
    character_id BIGINT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
    stat_key VARCHAR(120) NOT NULL,
    current_value BIGINT NOT NULL,
    max_value BIGINT,
    base_value BIGINT,
    source_type VARCHAR(64) NOT NULL DEFAULT 'CHARACTER_BASELINE',
    source_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_actor_resource_state UNIQUE (tenant_id, game_instance_id, character_id, stat_key)
);

CREATE INDEX idx_actor_resource_state_character
    ON actor_resource_states (tenant_id, game_instance_id, character_id);

CREATE TABLE actor_active_conditions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    game_instance_id VARCHAR(255) NOT NULL,
    character_id BIGINT NOT NULL REFERENCES characters(id) ON DELETE CASCADE,
    condition_key VARCHAR(120) NOT NULL,
    stack_count INTEGER NOT NULL DEFAULT 1,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(160),
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ,
    effect_payload_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_actor_active_conditions_character
    ON actor_active_conditions (tenant_id, game_instance_id, character_id);

CREATE INDEX idx_actor_active_conditions_expiry
    ON actor_active_conditions (tenant_id, game_instance_id, expires_at);
