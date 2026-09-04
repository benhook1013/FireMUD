-- Preserve every execution fence on the durable work and audit records. Defaults for
-- execution epochs and lifecycle revisions are intentionally fail-closed for pre-fence
-- rows: an execution path must reject a zero fence rather than treating it as current.
-- Failure generations start at one and are independent of the execution fences.
ALTER TABLE script_work_items
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_work_items
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_work_items
    ADD COLUMN failure_generation BIGINT NOT NULL DEFAULT 1;

ALTER TABLE script_event_audit
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_event_audit
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_event_audit
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_event_ingress_audit
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_event_ingress_audit
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_event_ingress_audit
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_handoff_events
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_handoff_events
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_handoff_events
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_script_work_items_execution_fences
    ON script_work_items (tenant_id, game_instance_id, script_patch_version,
                          script_pin_epoch, plugin_id, plugin_activation_epoch,
                          lifecycle_revision, status);

-- A replay request and its per-item readback are durable and digest-bound.  A request is
-- immutable; retrying the same request id with another digest is a conflict.
CREATE TABLE script_dead_letter_replay_requests (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    actor_principal VARCHAR(256) NOT NULL DEFAULT '',
    reason VARCHAR(256) NOT NULL DEFAULT '',
    status VARCHAR(64) NOT NULL,
    replayed_count BIGINT NOT NULL DEFAULT 0,
    rejected_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_dead_letter_replay_request UNIQUE (tenant_id, control_plane_request_id)
);

ALTER TABLE script_dead_letter_replay_requests
    ADD CONSTRAINT uq_script_dead_letter_replay_request_tenant_id
    UNIQUE (tenant_id, id);

ALTER TABLE script_work_items
    ADD CONSTRAINT uq_script_work_items_tenant_id
    UNIQUE (tenant_id, id);

CREATE TABLE script_dead_letter_replay_results (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    replay_request_id BIGINT NOT NULL,
    work_item_id BIGINT NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    rejection_reason VARCHAR(256) NOT NULL DEFAULT '',
    script_pin_epoch BIGINT NOT NULL DEFAULT 0,
    plugin_activation_epoch BIGINT NOT NULL DEFAULT 0,
    lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    failure_generation BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_script_dead_letter_replay_result UNIQUE (replay_request_id, work_item_id),
    CONSTRAINT fk_script_dead_letter_replay_result_request
        FOREIGN KEY (tenant_id, replay_request_id)
        REFERENCES script_dead_letter_replay_requests (tenant_id, id),
    CONSTRAINT fk_script_dead_letter_replay_result_work_item
        FOREIGN KEY (tenant_id, work_item_id)
        REFERENCES script_work_items (tenant_id, id)
);

CREATE INDEX idx_script_dead_letter_replay_results_request
    ON script_dead_letter_replay_results (replay_request_id, id);
