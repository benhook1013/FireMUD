CREATE TABLE remote_followup (
    id BIGSERIAL PRIMARY KEY,
    followup_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    origin_game_instance_id BIGINT NOT NULL,
    origin_region_id VARCHAR(64) NOT NULL,
    origin_region_epoch BIGINT NOT NULL,
    target_game_instance_id BIGINT NOT NULL,
    target_region_id VARCHAR(64) NOT NULL,
    target_region_epoch BIGINT NOT NULL,
    due_tick_id BIGINT NOT NULL,
    effect_key VARCHAR(128) NOT NULL,
    target_entity_id VARCHAR(64),
    status VARCHAR(40) NOT NULL,
    claimed_tick_batch_id VARCHAR(64),
    payload_json TEXT,
    failure_code VARCHAR(80),
    failure_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_remote_followup_target_region_status_due
    ON remote_followup (tenant_id, target_region_id, status, due_tick_id);

CREATE UNIQUE INDEX idx_remote_followup_target_region_epoch_effect
    ON remote_followup (tenant_id, target_region_id, target_region_epoch, effect_key);

CREATE TABLE remote_command_coordinator (
    id BIGSERIAL PRIMARY KEY,
    coordinator_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    command_id VARCHAR(64) NOT NULL,
    origin_game_instance_id BIGINT NOT NULL,
    origin_region_id VARCHAR(64) NOT NULL,
    origin_region_epoch BIGINT NOT NULL,
    target_game_instance_id BIGINT NOT NULL,
    target_region_id VARCHAR(64) NOT NULL,
    target_region_epoch BIGINT NOT NULL,
    target_due_tick_id BIGINT NOT NULL,
    origin_deadline_region_epoch BIGINT NOT NULL,
    origin_deadline_tick_id BIGINT NOT NULL,
    state VARCHAR(40) NOT NULL,
    late_result_policy VARCHAR(64) NOT NULL,
    execution_outcome VARCHAR(40),
    gameplay_result VARCHAR(40),
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX idx_remote_command_coordinator_command_id
    ON remote_command_coordinator (tenant_id, command_id);

CREATE INDEX idx_remote_command_coordinator_origin_region_state
    ON remote_command_coordinator (tenant_id, origin_region_id, state);

CREATE TABLE remote_followup_result (
    id BIGSERIAL PRIMARY KEY,
    result_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    coordinator_id VARCHAR(64) NOT NULL,
    followup_id VARCHAR(64) NOT NULL,
    origin_region_id VARCHAR(64) NOT NULL,
    origin_region_epoch BIGINT NOT NULL,
    target_region_id VARCHAR(64) NOT NULL,
    target_region_epoch BIGINT NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    result_payload_json TEXT,
    observed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_remote_followup_result_coordinator_observed
    ON remote_followup_result (tenant_id, coordinator_id, observed_at);

CREATE INDEX idx_remote_followup_result_followup_id
    ON remote_followup_result (tenant_id, followup_id);
