-- Plugin lifecycle request identity is immutable history.  The mutable runtime-state row is
-- only the current projection and must not be the idempotency authority after later transitions.
CREATE TABLE plugin_runtime_request_history (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    plugin_id VARCHAR(128) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    previous_plugin_version_id VARCHAR(128) NOT NULL DEFAULT '',
    active_plugin_version_id VARCHAR(128) NOT NULL DEFAULT '',
    plugin_activation_epoch BIGINT NOT NULL DEFAULT 0,
    lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    plugin_state VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_plugin_runtime_request_history_identity UNIQUE (
        tenant_id, game_instance_id, plugin_id, operation, control_plane_request_id
    )
);
