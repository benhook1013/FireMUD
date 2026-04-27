CREATE TABLE plugin_runtime_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    plugin_id VARCHAR(128) NOT NULL,
    previous_plugin_version_id VARCHAR(128),
    active_plugin_version_id VARCHAR(128),
    plugin_state VARCHAR(64) NOT NULL,
    status_reason VARCHAR(256) NOT NULL,
    control_plane_request_id VARCHAR(128),
    actor_principal VARCHAR(256),
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_plugin_runtime_events_scope
    ON plugin_runtime_events(tenant_id, game_instance_id, plugin_id, observed_at);
