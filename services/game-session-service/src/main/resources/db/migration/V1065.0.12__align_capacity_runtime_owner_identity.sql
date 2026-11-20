-- Preserve opaque runtime-owner text and all immutable execution evidence.
-- Qualify the exact generated predecessor CHECK rather than guessing truncated names.
-- [jooq ignore start]
DO $owners$
DECLARE target TEXT; constraint_name TEXT; definition TEXT;
BEGIN
  FOREACH target IN ARRAY ARRAY['game_session_original_new_capacity_execution', 'game_session_capacity_evidence_charge'] LOOP
    SELECT c.conname, pg_get_constraintdef(c.oid) INTO STRICT constraint_name, definition
      FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attname='selected_owner_id'
      WHERE c.conrelid=(quote_ident(current_schema())||'.'||quote_ident(target))::regclass
        AND c.contype='c' AND c.conkey=ARRAY[a.attnum]::smallint[];
    IF position('^[A-Za-z0-9._:-]{1,120}$' IN definition)=0 THEN
      RAISE EXCEPTION 'Expected exact ASCII owner identity predecessor constraint';
    END IF;
    EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', target, constraint_name);
    EXECUTE format($check$ALTER TABLE %I ADD CONSTRAINT %I CHECK (selected_owner_id <> '' AND btrim(selected_owner_id)=selected_owner_id)$check$, target, constraint_name);
  END LOOP;
END;
$owners$;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1065.0.11'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1065.0.11'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1065.0.12__align_capacity_runtime_owner_identity.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1065.0.12'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1065.0.11'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1065.0.12''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1065.0.11','V1065.0.11__bind_fence_receipt_dispatch_incarnation.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065.0.12'',''V1065.0.12__align_capacity_runtime_owner_identity.sql''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog marker'; END IF;
    EXECUTE replace(definition,original,replacement);
END;
$migration$;

INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex') FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes FROM (
        SELECT coalesce(max(catalog_epoch),0)+1 AS epoch FROM game_session_runtime_family_catalog_epoch
    ) next_epoch
) seal;
-- [jooq ignore stop]
