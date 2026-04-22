CREATE TABLE plugin_runtime_states (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    plugin_id VARCHAR(128) NOT NULL,
    active_plugin_version_id VARCHAR(128),
    pending_plugin_version_id VARCHAR(128),
    plugin_state VARCHAR(64) NOT NULL,
    status_reason VARCHAR(256) NOT NULL,
    control_plane_request_id VARCHAR(128),
    actor_principal VARCHAR(256),
    last_changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_plugin_runtime_state_scope UNIQUE (tenant_id, game_instance_id, plugin_id)
);

CREATE INDEX idx_plugin_runtime_state_tenant_instance
    ON plugin_runtime_states(tenant_id, game_instance_id);
