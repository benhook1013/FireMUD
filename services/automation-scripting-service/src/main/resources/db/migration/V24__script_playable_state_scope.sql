ALTER TABLE script_event_ingress_audit
    ADD COLUMN playable_state_scope VARCHAR(32) NOT NULL DEFAULT '';

ALTER TABLE script_work_items
    ADD COLUMN playable_state_scope VARCHAR(32) NOT NULL DEFAULT '';

ALTER TABLE script_event_audit
    ADD COLUMN playable_state_scope VARCHAR(32) NOT NULL DEFAULT '';

ALTER TABLE script_handoff_events
    ADD COLUMN playable_state_scope VARCHAR(32) NOT NULL DEFAULT '';

ALTER TABLE script_schedule_instances
    ADD COLUMN playable_state_scope VARCHAR(32) NOT NULL DEFAULT '';

ALTER TABLE script_patch_pin_projections
    ADD COLUMN playable_state_scope VARCHAR(32) NOT NULL DEFAULT '';

ALTER TABLE script_event_ingress_audit
    DROP CONSTRAINT IF EXISTS uq_script_event_ingress_audit_identity;

ALTER TABLE script_event_ingress_audit
    ADD CONSTRAINT uq_script_event_ingress_audit_identity UNIQUE (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    );

ALTER TABLE script_work_items
    DROP CONSTRAINT IF EXISTS uq_script_work_item_trigger_identity;

ALTER TABLE script_work_items
    ADD CONSTRAINT uq_script_work_item_trigger_identity UNIQUE (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        script_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    );

ALTER TABLE script_event_audit
    DROP CONSTRAINT IF EXISTS uq_script_event_audit_handler_identity;

ALTER TABLE script_event_audit
    ADD CONSTRAINT uq_script_event_audit_handler_identity UNIQUE (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        script_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    );

DROP INDEX IF EXISTS uq_script_schedule_instance_scope;

CREATE UNIQUE INDEX uq_script_schedule_instance_scope
    ON script_schedule_instances (
        tenant_id,
        game_instance_id,
        playable_state_scope,
        plugin_id,
        plugin_version_id,
        target_scope_type,
        target_scope_id,
        schedule_definition_id
    );
