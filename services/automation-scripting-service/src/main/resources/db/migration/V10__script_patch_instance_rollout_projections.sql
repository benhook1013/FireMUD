CREATE TABLE script_patch_instance_rollout_projections (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    rollout_status VARCHAR(64) NOT NULL,
    status_reason VARCHAR(256) NOT NULL,
    last_changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    projection_refreshed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_patch_instance_rollout_projection_scope
        UNIQUE (tenant_id, game_instance_id, script_patch_version)
);

CREATE INDEX idx_script_patch_instance_rollout_projection_scope
    ON script_patch_instance_rollout_projections(tenant_id, game_instance_id, script_patch_version);
