-- The run-owned fixture realm admits only preseeded characters, matching the canonical PLAY chain
-- (Account discovery, entitlement and Game Session admission accept PRESEEDED_ONLY). Pre-v1
-- fixture rows are throwaway, so retained rows are not revalidated.
-- [jooq ignore start]
ALTER TABLE game_session_canonical_realm_catalog
    DROP CONSTRAINT chk_gs_catalog_fixture_provenance;
ALTER TABLE game_session_canonical_realm_catalog
    ADD CONSTRAINT chk_gs_catalog_fixture_provenance CHECK (
        (fixture_policy_request_bytes IS NULL AND fixture_policy_seal_operation_id IS NULL
         AND fixture_policy_seal_request_digest IS NULL AND fixture_policy_seal_evidence_digest IS NULL)
        OR (complete_policy_set_evidence IS NULL AND fixture_policy_request_bytes IS NOT NULL
            AND octet_length(fixture_policy_request_bytes) > 0
            AND fixture_policy_seal_operation_id IS NOT NULL
            AND fixture_policy_seal_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND fixture_policy_seal_request_digest IS NOT NULL
            AND fixture_policy_seal_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND fixture_policy_seal_evidence_digest IS NOT NULL
            AND fixture_policy_seal_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND visible AND public_production AND state_scope = 'SHARED'
            AND character_creation_policy = 'PRESEEDED_ONLY')) NOT VALID;

DO $migration$
DECLARE definition TEXT; original TEXT; replacement TEXT;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$WHERE version='1070'
    ) AND ($needle$;
    replacement := $replacement$WHERE version='1070'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1071__run_owned_fixture_preseeded_only_catalog.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1071'
    ) AND ($replacement$;
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor catalog installation clause'; END IF;
    EXECUTE replace(definition,original,replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema())||'.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1070'$needle$;
    replacement := original||E'\n            AND version IS DISTINCT FROM ''1071''';
    IF (length(definition)-length(replace(definition,original,'')))/length(original)<>1 THEN
        RAISE EXCEPTION 'Expected exact predecessor history exclusion'; END IF;
    definition := replace(definition,original,replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1070','V1070__game_session_run_owned_fixture_initial_admission.sql'$needle$;
    replacement := original||E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1071'',''V1071__run_owned_fixture_preseeded_only_catalog.sql''';
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
