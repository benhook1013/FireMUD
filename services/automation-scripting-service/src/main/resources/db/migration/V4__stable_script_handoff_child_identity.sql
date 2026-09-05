-- One durable projection exists for each logical emitted command. Older retained
-- databases may contain multiple transport projections for the same child. Keep
-- a complete copy of every non-canonical row before repairing it so the unique
-- identity migration never silently discards handoff evidence. The lowest
-- durable row id is the deterministic survivor.
CREATE TABLE script_handoff_event_identity_repairs (
    id BIGSERIAL PRIMARY KEY,
    duplicate_row_id BIGINT NOT NULL,
    survivor_row_id BIGINT NOT NULL,
    event_id VARCHAR(80) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    script_id VARCHAR(128) NOT NULL,
    plugin_id VARCHAR(128),
    plugin_version_id VARCHAR(128),
    work_item_id BIGINT NOT NULL,
    command_ordinal INT NOT NULL,
    automation_dispatch_id VARCHAR(128) NOT NULL,
    game_session_command_id VARCHAR(128),
    target_game_instance_id VARCHAR(64) NOT NULL,
    target_region_id VARCHAR(64) NOT NULL,
    target_region_epoch BIGINT NOT NULL,
    remote_coordinator_id VARCHAR(128),
    remote_followup_id VARCHAR(128),
    target_entity_id VARCHAR(64) NOT NULL,
    playable_state_scope VARCHAR(32) NOT NULL,
    world_slug VARCHAR(64) NOT NULL,
    realm_slug VARCHAR(64) NOT NULL,
    pointer_version VARCHAR(64) NOT NULL,
    source_kind VARCHAR(64) NOT NULL,
    source_state VARCHAR(64) NOT NULL,
    source_ordinal BIGINT,
    source_due_tick_id BIGINT,
    source_due_at_ms BIGINT,
    emitted_command_text VARCHAR(1024) NOT NULL,
    handoff_outcome VARCHAR(128) NOT NULL,
    handoff_reason VARCHAR(256) NOT NULL,
    observed_at TIMESTAMP NOT NULL,
    row_version INT NOT NULL,
    repaired_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO script_handoff_event_identity_repairs (
    duplicate_row_id,
    survivor_row_id,
    event_id,
    tenant_id,
    game_instance_id,
    script_patch_version,
    script_id,
    plugin_id,
    plugin_version_id,
    work_item_id,
    command_ordinal,
    automation_dispatch_id,
    game_session_command_id,
    target_game_instance_id,
    target_region_id,
    target_region_epoch,
    remote_coordinator_id,
    remote_followup_id,
    target_entity_id,
    playable_state_scope,
    world_slug,
    realm_slug,
    pointer_version,
    source_kind,
    source_state,
    source_ordinal,
    source_due_tick_id,
    source_due_at_ms,
    emitted_command_text,
    handoff_outcome,
    handoff_reason,
    observed_at,
    row_version
)
SELECT duplicate.id,
       survivor.survivor_id,
       duplicate.event_id,
       duplicate.tenant_id,
       duplicate.game_instance_id,
       duplicate.script_patch_version,
       duplicate.script_id,
       duplicate.plugin_id,
       duplicate.plugin_version_id,
       duplicate.work_item_id,
       duplicate.command_ordinal,
       duplicate.automation_dispatch_id,
       duplicate.game_session_command_id,
       duplicate.target_game_instance_id,
       duplicate.target_region_id,
       duplicate.target_region_epoch,
       duplicate.remote_coordinator_id,
       duplicate.remote_followup_id,
       duplicate.target_entity_id,
       duplicate.playable_state_scope,
       duplicate.world_slug,
       duplicate.realm_slug,
       duplicate.pointer_version,
       duplicate.source_kind,
       duplicate.source_state,
       duplicate.source_ordinal,
       duplicate.source_due_tick_id,
       duplicate.source_due_at_ms,
       duplicate.emitted_command_text,
       duplicate.handoff_outcome,
       duplicate.handoff_reason,
       duplicate.observed_at,
       duplicate.row_version
FROM script_handoff_events duplicate
JOIN (
    SELECT tenant_id, work_item_id, command_ordinal, MIN(id) AS survivor_id
    FROM script_handoff_events
    GROUP BY tenant_id, work_item_id, command_ordinal
) survivor
  ON survivor.tenant_id = duplicate.tenant_id
 AND survivor.work_item_id = duplicate.work_item_id
 AND survivor.command_ordinal = duplicate.command_ordinal
WHERE duplicate.id <> survivor.survivor_id;

DELETE FROM script_handoff_events duplicate
WHERE duplicate.id > (
    SELECT MIN(survivor.id)
    FROM script_handoff_events survivor
    WHERE survivor.tenant_id = duplicate.tenant_id
      AND survivor.work_item_id = duplicate.work_item_id
      AND survivor.command_ordinal = duplicate.command_ordinal
);

CREATE UNIQUE INDEX uq_script_handoff_events_logical_child
    ON script_handoff_events (tenant_id, work_item_id, command_ordinal);

ALTER TABLE plugin_runtime_states
    ADD COLUMN control_plane_request_fingerprint VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE script_schedule_instances
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_work_items
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE plugin_runtime_states
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE plugin_runtime_states
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_schedule_instances
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE script_schedule_instances
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE automation_admission_states
    ADD COLUMN control_plane_request_fingerprint VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE script_event_ingress_audit
    ADD COLUMN request_fingerprint VARCHAR(64) NOT NULL DEFAULT '';
