-- Persist the owner-issued script pin epoch on instance-scoped work and schedule state.
-- Zero is the fail-closed representation for rows that have not observed an owner pin.
ALTER TABLE script_work_items
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_work_items
    ADD COLUMN script_pin_control_plane_request_id VARCHAR(256);

ALTER TABLE script_work_items
    ADD COLUMN binding_id VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE script_work_items
    ADD COLUMN target_scope_type VARCHAR(32) NOT NULL DEFAULT '';

ALTER TABLE script_work_items
    ADD COLUMN target_scope_id VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE script_work_items
    DROP CONSTRAINT uq_script_work_item_trigger_identity;

CREATE UNIQUE INDEX uq_script_work_item_trigger_identity ON script_work_items (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        world_slug,
        realm_slug,
        pointer_version,
        script_id,
        plugin_id,
        plugin_version_id,
        binding_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_pin_epoch,
        script_pin_control_plane_request_id,
        script_event_id,
        dry_run
    ) WHERE script_pin_epoch > 0;

CREATE UNIQUE INDEX uq_script_work_item_trigger_identity_unpinned ON script_work_items (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        world_slug,
        realm_slug,
        pointer_version,
        script_id,
        plugin_id,
        plugin_version_id,
        binding_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    ) WHERE script_pin_epoch = 0;

ALTER TABLE script_work_items
    ADD CONSTRAINT ck_script_work_items_pin_tuple CHECK (
        (script_pin_epoch = 0
            AND script_pin_control_plane_request_id IS NULL)
        OR (script_pin_epoch > 0
            AND NULLIF(BTRIM(script_pin_control_plane_request_id), '') IS NOT NULL)
    );

ALTER TABLE script_schedule_instances
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;

-- Rows created before epoch fencing may contain only the legacy request identity.  Do not retain
-- that identity as if it were evidence for epoch zero; the exact tuple is absent until a positive
-- owner epoch is observed.
UPDATE script_schedule_instances
SET last_observed_control_plane_request_id = ''
WHERE script_pin_epoch = 0;

ALTER TABLE script_schedule_instances
    ADD CONSTRAINT ck_script_schedule_instances_pin_tuple CHECK (
        (script_pin_epoch = 0
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NULL)
        OR (script_pin_epoch > 0
            AND NULLIF(BTRIM(last_observed_control_plane_request_id), '') IS NOT NULL)
    );
