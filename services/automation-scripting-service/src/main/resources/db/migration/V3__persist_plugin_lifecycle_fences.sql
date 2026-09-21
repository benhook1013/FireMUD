-- Persist the captured Automation-owned plugin lifecycle fence independently from
-- plugin provenance. Zero/zero is the explicit core-work sentinel; plugin-backed
-- work carries a positive activation epoch and lifecycle revision pair.
ALTER TABLE script_work_items
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_script_work_items_plugin_fence CHECK (
        (plugin_activation_epoch = 0 AND lifecycle_revision = 0)
        OR (plugin_activation_epoch > 0 AND lifecycle_revision > 0)
    );

ALTER TABLE plugin_runtime_states
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN control_plane_request_fingerprint VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE script_event_audit
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_script_event_audit_plugin_fence CHECK (
        (plugin_activation_epoch = 0 AND lifecycle_revision = 0)
        OR (plugin_activation_epoch > 0 AND lifecycle_revision > 0)
    );

ALTER TABLE script_event_ingress_audit
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_script_event_ingress_audit_plugin_fence CHECK (
        (plugin_activation_epoch = 0 AND lifecycle_revision = 0)
        OR (plugin_activation_epoch > 0 AND lifecycle_revision > 0)
    );

ALTER TABLE script_handoff_events
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_script_handoff_events_plugin_fence CHECK (
        (plugin_activation_epoch = 0 AND lifecycle_revision = 0)
        OR (plugin_activation_epoch > 0 AND lifecycle_revision > 0)
    );

ALTER TABLE script_schedule_instances
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_script_schedule_instances_plugin_fence CHECK (
        (plugin_activation_epoch = 0 AND lifecycle_revision = 0)
        OR (plugin_activation_epoch > 0 AND lifecycle_revision > 0)
    );

-- Request identity survives later plugin lifecycle transitions. The mutable
-- runtime-state row is only the current projection, not retry authority.
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
    request_outcome VARCHAR(32) NOT NULL DEFAULT 'SUCCEEDED',
    failure_code VARCHAR(64) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_plugin_runtime_request_history_outcome CHECK (
        (request_outcome = 'SUCCEEDED' AND failure_code = '')
        OR (request_outcome = 'FAILED' AND failure_code = 'FAILED_PRECONDITION')
    ),
    CONSTRAINT uq_plugin_runtime_request_history_identity UNIQUE (
        tenant_id, game_instance_id, plugin_id, control_plane_request_id
    )
);
