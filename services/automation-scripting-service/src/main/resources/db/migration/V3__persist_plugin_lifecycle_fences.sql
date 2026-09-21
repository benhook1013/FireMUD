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

-- Retained runtime rows do not carry historical activation evidence. A
-- nonblank active version is therefore represented as one founding lifecycle
-- pair, while an empty active version remains the explicit 0/0 no-proof
-- sentinel. Never synthesize a pair for old work, ingress, audit, or handoff
-- rows: their winning admission fence cannot be reconstructed from retention.
-- An executable retained state without an active version is contradictory and
-- aborts this migration rather than silently blessing an unsafe row.
/* [jooq ignore start] */
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM plugin_runtime_states
        WHERE plugin_state IN ('ENABLED', 'DRAINING')
          AND NULLIF(BTRIM(active_plugin_version_id), '') IS NULL
    ) THEN
        RAISE EXCEPTION
            'V3 cannot establish plugin lifecycle fence for executable runtime state without active plugin version';
    END IF;
END
$$;
/* [jooq ignore stop] */

-- The first positive pair is a migration founding value, not a fabricated
-- historical transition. Every retained current runtime row with a version
-- receives it so a later reactivation cannot reuse epoch 1.
UPDATE plugin_runtime_states
SET plugin_activation_epoch = 1,
    lifecycle_revision = 1
WHERE NULLIF(BTRIM(active_plugin_version_id), '') IS NOT NULL;

-- Only a schedule with complete retained tenant/instance/plugin/version
-- provenance matching the current runtime projection can inherit that
-- founding pair. Mismatched or orphaned schedules remain 0/0 and are fenced
-- for owner-led reconciliation.
/* [jooq ignore start] */
UPDATE script_schedule_instances schedule_instance
SET plugin_activation_epoch = runtime_state.plugin_activation_epoch,
    lifecycle_revision = runtime_state.lifecycle_revision
FROM plugin_runtime_states runtime_state
WHERE schedule_instance.tenant_id = runtime_state.tenant_id
  AND schedule_instance.game_instance_id = runtime_state.game_instance_id
  AND NULLIF(BTRIM(schedule_instance.plugin_id), '') IS NOT NULL
  AND NULLIF(BTRIM(schedule_instance.plugin_version_id), '') IS NOT NULL
  AND schedule_instance.plugin_id = runtime_state.plugin_id
  AND schedule_instance.plugin_version_id = runtime_state.active_plugin_version_id
  AND runtime_state.plugin_activation_epoch = 1
  AND runtime_state.lifecycle_revision = 1;
/* [jooq ignore stop] */

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
