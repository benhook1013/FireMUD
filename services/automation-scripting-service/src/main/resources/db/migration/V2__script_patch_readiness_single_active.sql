-- Existing duplicate active tenant rows make this migration fail closed. Reconcile them under
-- owner authority; the migration neither chooses a winner nor rewrites retained readiness.
-- V2 also replaces producer-agnostic unique indexes. Stop old Automation pods before this
-- migration starts: an old writer can still rely on the narrower producer-agnostic key.
-- These checks run before any index is dropped and preserve retained rows on failure.
-- [jooq ignore start]
DO $v2_preflight$
DECLARE
    exact_unique_index_count integer;
BEGIN
    SELECT count(*)
      INTO exact_unique_index_count
      FROM (VALUES
          ('uq_script_work_item_trigger_identity', 'script_work_items', ARRAY['tenant_id', 'game_instance_id', 'region_id', 'region_epoch', 'entity_id', 'playable_state_scope', 'world_slug', 'realm_slug', 'pointer_version', 'script_id', 'plugin_id', 'plugin_version_id', 'binding_id', 'event_type', 'event_schema_version', 'script_patch_version', 'script_pin_epoch', 'script_event_id', 'dry_run']::name[], 'script_pin_epoch > 0', false),
          ('uq_script_work_item_trigger_identity_unpinned', 'script_work_items', ARRAY['tenant_id', 'game_instance_id', 'region_id', 'region_epoch', 'entity_id', 'playable_state_scope', 'world_slug', 'realm_slug', 'pointer_version', 'script_id', 'plugin_id', 'plugin_version_id', 'binding_id', 'event_type', 'event_schema_version', 'script_patch_version', 'script_event_id', 'dry_run']::name[], 'script_pin_epoch = 0', false),
          ('uq_script_event_audit_handler_identity', 'script_event_audit', ARRAY['tenant_id', 'game_instance_id', 'region_id', 'region_epoch', 'entity_id', 'playable_state_scope', 'world_slug', 'realm_slug', 'pointer_version', 'script_id', 'plugin_id', 'plugin_version_id', 'binding_id', 'event_type', 'event_schema_version', 'script_patch_version', 'script_pin_epoch', 'script_event_id', 'dry_run']::name[], 'script_pin_epoch > 0', true),
          ('uq_script_event_audit_handler_identity_unpinned', 'script_event_audit', ARRAY['tenant_id', 'game_instance_id', 'region_id', 'region_epoch', 'entity_id', 'playable_state_scope', 'world_slug', 'realm_slug', 'pointer_version', 'script_id', 'plugin_id', 'plugin_version_id', 'binding_id', 'event_type', 'event_schema_version', 'script_patch_version', 'script_event_id', 'dry_run']::name[], 'script_pin_epoch IS NULL', true)
      ) AS expected(index_name, table_name, key_columns, predicate, nulls_not_distinct)
      JOIN pg_class AS index_relation ON index_relation.relname = expected.index_name
      JOIN pg_namespace AS index_namespace ON index_namespace.oid = index_relation.relnamespace
      JOIN pg_index AS index_metadata ON index_metadata.indexrelid = index_relation.oid
      JOIN pg_class AS table_relation ON table_relation.oid = index_metadata.indrelid
      JOIN pg_namespace AS table_namespace ON table_namespace.oid = table_relation.relnamespace
      CROSS JOIN LATERAL (
          SELECT array_agg(index_column.attname ORDER BY key_column.ordinality) AS key_columns
            FROM unnest(index_metadata.indkey) WITH ORDINALITY AS key_column(attnum, ordinality)
            JOIN pg_attribute AS index_column
              ON index_column.attrelid = table_relation.oid
             AND index_column.attnum = key_column.attnum
           WHERE key_column.ordinality <= index_metadata.indnkeyatts
      ) AS actual
     WHERE index_namespace.nspname = current_schema()
       AND table_namespace.nspname = current_schema()
       AND table_relation.relname = expected.table_name
       AND index_metadata.indisunique
       AND index_metadata.indisvalid
       AND index_metadata.indisready
       AND actual.key_columns = expected.key_columns
       AND regexp_replace(
               COALESCE(pg_get_expr(index_metadata.indpred, table_relation.oid), ''),
               '[[:space:]()]', '', 'g'
           ) = regexp_replace(expected.predicate, '[[:space:]()]', '', 'g')
       AND index_metadata.indnullsnotdistinct = expected.nulls_not_distinct;

    IF exact_unique_index_count <> 4 THEN
        RAISE EXCEPTION
            'Automation V2 requires the four exact V1 producer-agnostic unique indexes; found %',
            exact_unique_index_count;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM script_patch_readiness_projections
         WHERE readiness_status IN ('PENDING_VALIDATION', 'ONLOAD_RUNNING')
         GROUP BY tenant_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Automation V2 found retained duplicate active readiness rows for uq_script_patch_readiness_active_tenant; reconcile under owner authority';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM script_work_items
         WHERE status IN ('EVALUATING', 'HANDOFF_IN_FLIGHT')
    ) OR EXISTS (
        SELECT 1
          FROM script_patch_readiness_projections
         WHERE readiness_status = 'ONLOAD_RUNNING'
    ) THEN
        RAISE EXCEPTION
            'Automation V2 requires all active work-item and onLoad claims to drain before migration';
    END IF;
END
$v2_preflight$;
-- [jooq ignore stop]

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

-- Retained V1 projections stay readable with NULL manifest/generation and an incomplete
-- downstream marker. New Automation code binds retries to one canonical script set and uses
-- the tenant-serialized generation as its current-readiness fence.
ALTER TABLE script_patch_readiness_projections
    ADD COLUMN script_set_manifest TEXT[],
    ADD COLUMN readiness_generation BIGINT,
    ADD COLUMN database_downstream_reconciled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uq_script_patch_readiness_tenant_generation
    ON script_patch_readiness_projections (tenant_id, readiness_generation)
    WHERE readiness_generation IS NOT NULL;
