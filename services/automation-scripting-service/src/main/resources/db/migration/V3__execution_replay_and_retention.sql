-- Durable execution/replay substrate for the Automation owner.  V1 is the
-- direct schema baseline and V2 owns plugin lifecycle fences; this migration
-- adds only the execution failure, replay, retry, and retention evidence that
-- those schemas do not already provide.

ALTER TABLE script_work_items
    ADD COLUMN failure_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN authority_unavailable_since TIMESTAMP,
    ADD COLUMN authority_unavailable_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_eligible_at TIMESTAMP;

-- New work starts before its first dead-letter transition. Existing dead letters
-- already represent one completed failure generation and retain that evidence.
UPDATE script_work_items
   SET failure_generation = 1
 WHERE status = 'DEAD_LETTERED'
   AND failure_generation = 0;

CREATE INDEX idx_script_work_items_execution_fences
    ON script_work_items (tenant_id, game_instance_id, script_patch_version,
                          script_pin_epoch, plugin_id, plugin_activation_epoch,
                          lifecycle_revision, status);

CREATE INDEX idx_script_work_items_retry_eligibility
    ON script_work_items (status, next_eligible_at, created_at, id);

-- A replay request is immutable idempotency evidence.  The tenant-qualified
-- keys keep owner scope explicit even where a surrogate row id is globally
-- unique in this database.
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
    CONSTRAINT uq_script_dead_letter_replay_request UNIQUE (
        tenant_id, control_plane_request_id
    ),
    CONSTRAINT uq_script_dead_letter_replay_request_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE script_dead_letter_replay_results (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    replay_request_id BIGINT NOT NULL,
    requested_work_item_id BIGINT NOT NULL,
    -- A requested id may have no owned parent row.  The nullable resolved id
    -- preserves that deterministic rejection without weakening tenant scoping.
    work_item_id BIGINT,
    outcome VARCHAR(64) NOT NULL,
    rejection_reason VARCHAR(256) NOT NULL DEFAULT '',
    -- Snapshot the dead-letter failure before replay changes the mutable work item/audit.
    -- These fields are distinct from failure_reason, which describes a failed recovery attempt.
    original_failure_stage VARCHAR(64) NOT NULL DEFAULT '',
    original_failure_reason VARCHAR(256) NOT NULL DEFAULT '',
    failure_reason VARCHAR(256) NOT NULL DEFAULT '',
    script_pin_epoch BIGINT NOT NULL DEFAULT 0,
    plugin_activation_epoch BIGINT NOT NULL DEFAULT 0,
    lifecycle_revision BIGINT NOT NULL DEFAULT 0,
    failure_generation BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_script_dead_letter_replay_result
        UNIQUE (replay_request_id, requested_work_item_id),
    CONSTRAINT fk_script_dead_letter_replay_result_request
        FOREIGN KEY (tenant_id, replay_request_id)
        REFERENCES script_dead_letter_replay_requests (tenant_id, id),
    CONSTRAINT fk_script_dead_letter_replay_result_work_item
        FOREIGN KEY (tenant_id, work_item_id)
        REFERENCES script_work_items (tenant_id, id)
);

CREATE INDEX idx_script_dead_letter_replay_results_request
    ON script_dead_letter_replay_results (replay_request_id, id);

CREATE INDEX idx_script_dead_letter_replay_results_work_item
    ON script_dead_letter_replay_results (tenant_id, work_item_id);

-- Holds are owner-controlled safety fences.  Cleanup must still prove the
-- relevant replay/child and lifecycle conditions; a hold is never a retention
-- horizon and this migration invents no TTL.
ALTER TABLE script_event_audit
    ADD COLUMN retention_hold_until TIMESTAMPTZ NULL;

ALTER TABLE script_handoff_events
    ADD COLUMN retention_hold_until TIMESTAMPTZ NULL;

ALTER TABLE script_dead_letter_replay_requests
    ADD COLUMN retention_hold_until TIMESTAMPTZ NULL;

ALTER TABLE script_dead_letter_replay_results
    ADD COLUMN retention_hold_until TIMESTAMPTZ NULL;

CREATE INDEX idx_script_event_audit_retention
    ON script_event_audit (updated_at, retention_hold_until, tenant_id);

CREATE INDEX idx_script_handoff_events_retention
    ON script_handoff_events (observed_at, retention_hold_until, tenant_id);

CREATE INDEX idx_script_dead_letter_replay_requests_retention
    ON script_dead_letter_replay_requests (updated_at, retention_hold_until, tenant_id);

CREATE INDEX idx_script_dead_letter_replay_results_retention
    ON script_dead_letter_replay_results (created_at, retention_hold_until, tenant_id);
