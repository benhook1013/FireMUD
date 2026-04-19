CREATE TABLE runtime_region_status (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    region_epoch BIGINT NOT NULL,
    executor_fence VARCHAR(64) NOT NULL,
    owner_service VARCHAR(80) NOT NULL,
    owner_instance_id VARCHAR(120) NOT NULL,
    paused BOOLEAN NOT NULL,
    last_committed_tick_batch_id VARCHAR(64),
    updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_runtime_region_status_tenant_instance
    ON runtime_region_status (tenant_id, game_instance_id);
