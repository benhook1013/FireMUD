-- Fail closed before V8 can normalize legacy plugin provenance. This callback is discovered
-- before every migration, including V1, so it must be a no-op until V4 has added the epoch and
-- binding columns. It deliberately selects no canonical row and never deletes or merges data.
/* [jooq ignore start] */
DO $$
BEGIN
    IF to_regclass('script_work_items') IS NULL
       OR NOT EXISTS (
           SELECT 1
           FROM pg_attribute
           WHERE attrelid = to_regclass('script_work_items')
             AND attname = 'script_pin_epoch'
             AND NOT attisdropped
       ) THEN
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM script_work_items
        WHERE (NULLIF(BTRIM(plugin_id), '') IS NULL)
           <> (NULLIF(BTRIM(plugin_version_id), '') IS NULL)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'script_work_items contains a one-sided plugin identity pair';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM script_work_items
        WHERE script_pin_epoch > 0
        GROUP BY
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
            CASE
                WHEN plugin_id IS NULL OR plugin_version_id IS NULL THEN ''
                ELSE plugin_id
            END,
            CASE
                WHEN plugin_id IS NULL OR plugin_version_id IS NULL THEN ''
                ELSE plugin_version_id
            END,
            binding_id,
            event_type,
            event_schema_version,
            script_patch_version,
            script_pin_epoch,
            script_event_id,
            dry_run
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = 'script_work_items contains duplicate normalized pinned trigger identity';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM script_work_items
        WHERE script_pin_epoch = 0
        GROUP BY
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
            CASE
                WHEN plugin_id IS NULL OR plugin_version_id IS NULL THEN ''
                ELSE plugin_id
            END,
            CASE
                WHEN plugin_id IS NULL OR plugin_version_id IS NULL THEN ''
                ELSE plugin_version_id
            END,
            binding_id,
            event_type,
            event_schema_version,
            script_patch_version,
            script_event_id,
            dry_run
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23505',
            MESSAGE = 'script_work_items contains duplicate normalized unpinned trigger identity';
    END IF;
END
$$;
/* [jooq ignore stop] */
