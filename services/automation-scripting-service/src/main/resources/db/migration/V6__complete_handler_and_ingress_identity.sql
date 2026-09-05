-- Handler identity is the complete resolved branch identity.  The nullable plugin
-- fields are intentionally retained as NULL for core handlers; PostgreSQL's
-- NULLS NOT DISTINCT index makes absent values one explicit core branch rather
-- than allowing duplicate logical handlers through ordinary UNIQUE semantics.
ALTER TABLE script_work_items
    ADD COLUMN binding_id VARCHAR(128);
ALTER TABLE script_event_audit
    ADD COLUMN binding_id VARCHAR(128);

-- [jooq ignore start]
-- PostgreSQL's NULLS NOT DISTINCT uniqueness is intentionally fail-closed for
-- retained duplicates.  Detect conflicts before replacing the old constraints
-- so operators receive an owner-specific migration error and no partial index
-- replacement is attempted.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM script_work_items
        GROUP BY tenant_id, game_instance_id, region_id, region_epoch, entity_id,
                 playable_state_scope, world_slug, realm_slug, pointer_version, script_id,
                 plugin_id, plugin_version_id, binding_id, event_type, event_schema_version,
                 script_patch_version, script_event_id, dry_run
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V6 cannot install script_work_items handler identity: retained duplicate rows exist';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM script_event_audit
        GROUP BY tenant_id, game_instance_id, region_id, region_epoch, entity_id,
                 playable_state_scope, world_slug, realm_slug, pointer_version, script_id,
                 plugin_id, plugin_version_id, binding_id, event_type, event_schema_version,
                 script_patch_version, script_event_id, dry_run
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V6 cannot install script_event_audit handler identity: retained duplicate rows exist';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM script_event_ingress_audit
        GROUP BY tenant_id, game_instance_id, region_id, region_epoch, entity_id,
                 playable_state_scope, event_type, event_schema_version, script_patch_version,
                 script_event_id, dry_run, source_service
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V6 cannot install script_event_ingress_audit identity: retained duplicate rows exist';
    END IF;
END $$;

ALTER TABLE script_work_items
    DROP CONSTRAINT uq_script_work_item_trigger_identity;
CREATE UNIQUE INDEX uq_script_work_item_complete_handler_identity
    ON script_work_items (
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
    ) NULLS NOT DISTINCT;

ALTER TABLE script_event_audit
    DROP CONSTRAINT uq_script_event_audit_handler_identity;
CREATE UNIQUE INDEX uq_script_event_audit_complete_handler_identity
    ON script_event_audit (
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
    ) NULLS NOT DISTINCT;

-- Event-scope identities keep absent optional scope fields absent.  NULLS NOT
-- DISTINCT closes the ordinary PostgreSQL ON CONFLICT hole without converting
-- an absent scope into a real sentinel value.
ALTER TABLE script_event_ingress_audit
    DROP CONSTRAINT uq_script_event_ingress_audit_identity;
CREATE UNIQUE INDEX uq_script_event_ingress_audit_null_safe_identity
    ON script_event_ingress_audit (
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
        dry_run,
        source_service
    ) NULLS NOT DISTINCT;
-- [jooq ignore stop]
