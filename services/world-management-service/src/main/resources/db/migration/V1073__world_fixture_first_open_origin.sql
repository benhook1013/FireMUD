-- The existing World hold ledger admits a new run-owned fixture only for its first OPEN.
-- The exact association was locked and checked by the existing acquisition guard.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    IF NEW.hold_binding_digest IS DISTINCT FROM$anchor$;
    guard TEXT := $guard$    IF association_row.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        AND (NEW.initial_admission_origin IS DISTINCT FROM 'NO_PRIOR_POINTER'
            OR NEW.expected_no_prior_pointer IS DISTINCT FROM TRUE
            OR NEW.expected_prior_pointer_version IS NOT NULL) THEN
        RAISE EXCEPTION 'Fixture first OPEN supports NO_PRIOR_POINTER only'
            USING ERRCODE = '23514';
    END IF;

$guard$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_guard_canonical_initial_admission_hold()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exact initial-admission hold acquisition guard';
    END IF;
    EXECUTE replace(original, anchor, guard || anchor);
END;
$migration$;

CREATE OR REPLACE FUNCTION world_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE
SET search_path = "${serviceSchema}", pg_catalog
AS $$
    SELECT EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1046' AND script='V1046__world_owner_runtime_family_catalog.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1047' AND script='V1047__world_catalog_snapshot_exact_frames.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}"
        WHERE version='1048' AND script='V1048__world_protected_installation_registry.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1049'
        AND script='V1049__world_private_fresh_operation.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1050'
        AND script='V1050__world_private_fresh_settlement_binding.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1070'
        AND script='V1070__world_run_owned_fixture_activation.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1071'
        AND script='V1071__world_guarded_existing_region_updates.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1072'
        AND script='V1072__world_guarded_existing_region_batches.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1073'
        AND script='V1073__world_fixture_first_open_origin.sql' AND success);
$$;

-- Extend the installer fact/exclusion, then append a new immutable catalog epoch.
DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$SELECT 'MIGRATION','catalog-installation:1072','V1072__world_guarded_existing_region_batches.sql'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_bytes(bigint)'::REGPROCEDURE) INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1072 catalog migration fact';
    END IF;
    IF (length(original)-length(replace(original,$history$AND version IS DISTINCT FROM '1072'$history$,'')))
        /length($history$AND version IS DISTINCT FROM '1072'$history$)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1072 catalog history exclusion';
    END IF;
    original := replace(original,$history$AND version IS DISTINCT FROM '1072'$history$,
        $history$AND version IS DISTINCT FROM '1072' AND version IS DISTINCT FROM '1073'$history$);
    original := replace(original,anchor,anchor || $replacement$
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1073','V1073__world_fixture_first_open_origin.sql'$replacement$);
    EXECUTE original;
END;
$migration$;
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
