-- Preserve exact schema-1 inventory rows and permit only the additive closure inventory profile.
-- [jooq ignore start]
DO $migration$
DECLARE
    target_schema TEXT := '${serviceSchema}';
    target_table TEXT := 'world_selected_publication_artifact_inventory';
    target_relation OID;
    target_column SMALLINT;
    matching_guard_count BIGINT;
    guard_names TEXT[];
    guard_name TEXT;
    guard_is_expected BOOLEAN;
    replacement_guard TEXT := 'ck_world_selected_pub_inv_schema_v2';
BEGIN
    SELECT c.oid INTO target_relation
    FROM pg_catalog.pg_class c
    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = target_schema
        AND c.relname = target_table
        AND c.relkind = 'r';
    IF target_relation IS NULL THEN
        RAISE EXCEPTION 'Expected exact World selected inventory owner table %.%',
            target_schema, target_table;
    END IF;

    SELECT a.attnum INTO target_column
    FROM pg_catalog.pg_attribute a
    WHERE a.attrelid = target_relation
        AND a.attname = 'inventory_schema_version'
        AND NOT a.attisdropped;
    IF target_column IS NULL THEN
        RAISE EXCEPTION 'Expected inventory_schema_version on exact World inventory owner table';
    END IF;

    SELECT count(*), array_agg(c.conname::TEXT), bool_and(
        c.conislocal
        AND c.convalidated
        AND c.conkey = ARRAY[target_column]::SMALLINT[]
        AND pg_catalog.regexp_replace(
            pg_catalog.lower(pg_catalog.pg_get_expr(c.conbin, c.conrelid)),
            '[[:space:]()]', '', 'g') = 'inventory_schema_version=1')
    INTO matching_guard_count, guard_names, guard_is_expected
    FROM pg_catalog.pg_constraint c
    WHERE c.conrelid = target_relation
        AND c.contype = 'c'
        AND target_column = ANY(c.conkey);

    IF matching_guard_count <> 1 OR guard_is_expected IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION
            'Expected exactly one single-column inventory_schema_version = 1 check on %.%',
            target_schema, target_table;
    END IF;
    guard_name := guard_names[1];

    EXECUTE pg_catalog.format(
        'ALTER TABLE %I.%I DROP CONSTRAINT %s',
        target_schema, target_table, pg_catalog.quote_ident(guard_name));
    EXECUTE pg_catalog.format(
        'ALTER TABLE %I.%I ADD CONSTRAINT %s CHECK (inventory_schema_version IN (1, 2))',
        target_schema, target_table, pg_catalog.quote_ident(replacement_guard));
END;
$migration$;
-- [jooq ignore stop]
