CREATE TABLE tick_batch (
    id BIGSERIAL PRIMARY KEY,
    tick_batch_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    region_epoch BIGINT NOT NULL,
    executor_fence VARCHAR(64) NOT NULL,
    batch_source VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    requires_solo_tick BOOLEAN NOT NULL,
    command_count INTEGER NOT NULL,
    staged_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    failure_code VARCHAR(80),
    failure_message VARCHAR(500)
);

CREATE TABLE tick_effect (
    id BIGSERIAL PRIMARY KEY,
    effect_id VARCHAR(64) NOT NULL UNIQUE,
    tick_batch_id VARCHAR(64) NOT NULL,
    command_id VARCHAR(64),
    effect_type VARCHAR(80) NOT NULL,
    target_aggregate VARCHAR(120) NOT NULL,
    status VARCHAR(40) NOT NULL,
    staged_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    failure_code VARCHAR(80),
    failure_message VARCHAR(500)
);

CREATE UNIQUE INDEX idx_tick_batch_tick_batch_id ON tick_batch (tick_batch_id);
CREATE INDEX idx_tick_batch_tenant_instance_status ON tick_batch (tenant_id, game_instance_id, status);
CREATE UNIQUE INDEX idx_tick_effect_effect_id ON tick_effect (effect_id);
CREATE INDEX idx_tick_effect_tick_batch_id ON tick_effect (tick_batch_id);
CREATE INDEX idx_tick_effect_command_id ON tick_effect (command_id);
