CREATE TABLE script_patch_pin_projections (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    observed_pinned_script_patch_version VARCHAR(128) NOT NULL DEFAULT '',
    last_observed_control_plane_request_id VARCHAR(128) NOT NULL DEFAULT '',
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    projection_refreshed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_patch_pin_projection_scope UNIQUE (tenant_id, game_instance_id)
);

CREATE INDEX idx_script_patch_pin_projection_scope
    ON script_patch_pin_projections(tenant_id, game_instance_id);
