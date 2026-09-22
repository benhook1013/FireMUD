-- Existing duplicate active tenant rows make this migration fail closed. Reconcile them under
-- owner authority; the migration neither chooses a winner nor rewrites retained readiness.
-- The V1 handler identities omitted the authenticated producer service. Replace those indexes
-- before introducing the active-readiness guard so producer-local event IDs cannot alias.
DROP INDEX uq_script_work_item_trigger_identity;
DROP INDEX uq_script_work_item_trigger_identity_unpinned;
/* [jooq ignore start] */
DROP INDEX uq_script_event_audit_handler_identity;
DROP INDEX uq_script_event_audit_handler_identity_unpinned;
/* [jooq ignore stop] */

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
    script_event_id,
    dry_run,
    source_service
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
    dry_run,
    source_service
) WHERE script_pin_epoch = 0;

/* [jooq ignore start] */
CREATE UNIQUE INDEX uq_script_event_audit_handler_identity ON script_event_audit (
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
    script_event_id,
    dry_run,
    source_service
) NULLS NOT DISTINCT WHERE script_pin_epoch > 0;
/* [jooq ignore stop] */

/* [jooq ignore start] */
CREATE UNIQUE INDEX uq_script_event_audit_handler_identity_unpinned ON script_event_audit (
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
    dry_run,
    source_service
) NULLS NOT DISTINCT WHERE script_pin_epoch IS NULL;
/* [jooq ignore stop] */

CREATE UNIQUE INDEX uq_script_patch_readiness_active_tenant
    ON script_patch_readiness_projections (tenant_id)
    WHERE readiness_status IN ('PENDING_VALIDATION', 'ONLOAD_RUNNING');
