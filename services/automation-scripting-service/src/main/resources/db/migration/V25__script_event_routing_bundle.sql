ALTER TABLE script_event_ingress_audit
    ADD COLUMN world_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN pointer_version VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE script_work_items
    ADD COLUMN world_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN pointer_version VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE script_event_audit
    ADD COLUMN world_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN pointer_version VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE script_handoff_events
    ADD COLUMN world_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN pointer_version VARCHAR(64) NOT NULL DEFAULT '';

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
        world_slug,
        realm_slug,
        pointer_version,
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
        world_slug,
        realm_slug,
        pointer_version,
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
        world_slug,
        realm_slug,
        pointer_version,
        script_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    );
