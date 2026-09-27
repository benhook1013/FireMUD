-- This V2 migration takes blocking PostgreSQL DDL locks. Apply only at the
-- quiesced deployment boundary documented in system-architecture-database-migrations.md:
-- old Game Session pods must be stopped before a V2 pod starts.
-- The old unique keys below are strictly narrower than their replacements, so
-- retained rows cannot collide on the new scoped identities. Require the exact
-- old unique objects before changing them; an unknown schema state fails closed.
-- [jooq ignore start]
DO $v2_preflight$
DECLARE
    exact_unique_index_count integer;
BEGIN
    SELECT count(*)
      INTO exact_unique_index_count
      FROM (VALUES
          ('idx_gameplay_command_command_id', 'gameplay_command', ARRAY['command_id']::name[]),
          ('idx_remote_command_coordinator_command_id', 'remote_command_coordinator', ARRAY['tenant_id', 'command_id']::name[]),
          ('uq_gameplay_admission_pointer_world_realm', 'gameplay_admission_pointer', ARRAY['world_slug', 'realm_slug']::name[])
      ) AS expected(index_name, table_name, key_columns)
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
       AND index_metadata.indpred IS NULL
       AND index_metadata.indexprs IS NULL
       AND actual.key_columns = expected.key_columns;

    IF exact_unique_index_count <> 3 THEN
        RAISE EXCEPTION
            'Game Session V2 requires the three exact V1 pre-migration unique indexes; found %',
            exact_unique_index_count;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = format('%I.%I', current_schema(), 'gameplay_command')::regclass
           AND conname = 'gameplay_command_command_id_key'
           AND contype = 'u'
           AND pg_get_constraintdef(oid) = 'UNIQUE (command_id)'
    ) THEN
        RAISE EXCEPTION
            'Game Session V2 requires the gameplay_command_command_id_key unique constraint';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM remote_followup
         WHERE status = 'CLAIMED'
    ) THEN
        RAISE EXCEPTION
            'Game Session V2 requires claimed remote follow-ups to drain before migration';
    END IF;
END
$v2_preflight$;
-- [jooq ignore stop]

-- Gameplay command identity is scoped to the owning tenant and game instance.
-- Keep a non-unique command_id index for correlation and diagnostics only.
CREATE UNIQUE INDEX idx_gameplay_command_tenant_instance_command_id
    ON gameplay_command USING btree (tenant_id, game_instance_id, command_id);

DROP INDEX IF EXISTS idx_gameplay_command_command_id;

ALTER TABLE gameplay_command
    DROP CONSTRAINT IF EXISTS gameplay_command_command_id_key;

CREATE INDEX idx_gameplay_command_command_id
    ON gameplay_command USING btree (command_id);

-- Remote coordinator identity follows the origin command's complete runtime scope.
-- Keep a tenant/command correlation index for control-plane diagnostics, but do not
-- use it as an idempotency key because command IDs may be reused by game instance.
CREATE UNIQUE INDEX idx_remote_command_coordinator_tenant_origin_instance_cmd
    ON remote_command_coordinator USING btree (tenant_id, origin_game_instance_id, command_id);

DROP INDEX IF EXISTS idx_remote_command_coordinator_command_id;

CREATE INDEX idx_remote_command_coordinator_command_id
    ON remote_command_coordinator USING btree (tenant_id, command_id);

-- Admission-pointer selectors are tenant-local identities, not global identities.
CREATE UNIQUE INDEX uq_gameplay_admission_pointer_tenant_world_realm
    ON gameplay_admission_pointer USING btree (tenant_id, world_slug, realm_slug);

DROP INDEX IF EXISTS uq_gameplay_admission_pointer_world_realm;
