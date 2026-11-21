-- Fresh fixture acquisition is first OPEN only. Historical codecs/retained rows are unchanged.
-- [jooq ignore start]
CREATE FUNCTION world_guard_fixture_first_open_origin()
RETURNS TRIGGER LANGUAGE plpgsql
SET search_path=pg_catalog,"${serviceSchema}"
AS $$
BEGIN
    IF NEW.fixture_hold_request_bytes IS NOT NULL
        AND (NEW.initial_admission_origin IS DISTINCT FROM 'NO_PRIOR_POINTER'
            OR NEW.expected_no_prior_pointer IS DISTINCT FROM TRUE
            OR NEW.expected_prior_pointer_version IS NOT NULL) THEN
        RAISE EXCEPTION 'Fixture first OPEN supports NO_PRIOR_POINTER only'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_guard_fixture_first_open_origin() FROM PUBLIC;
CREATE TRIGGER world_fixture_first_open_origin
BEFORE INSERT ON initial_admission_bind_hold
FOR EACH ROW EXECUTE FUNCTION world_guard_fixture_first_open_origin();

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
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1051'
        AND script='V1051__world_run_owned_fixture_activation.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1052'
        AND script='V1052__world_fixture_activation_catalog.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1053'
        AND script='V1053__world_fixture_initial_hold.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1054'
        AND script='V1054__world_guarded_existing_region_updates.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1055'
        AND script='V1055__world_guarded_existing_region_batches.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1056'
        AND script='V1056__world_fixture_first_open_origin.sql' AND success);
$$;

-- Preserve every old catalog epoch while recording the new storage guard in a fresh seal.
DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$SELECT 'MIGRATION','catalog-installation:1055','V1055__world_guarded_existing_region_batches.sql'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_bytes(bigint)'::REGPROCEDURE) INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1055 catalog migration fact';
    END IF;
    IF (length(original)-length(replace(original,$history$AND version IS DISTINCT FROM '1055'$history$,'')))
        /length($history$AND version IS DISTINCT FROM '1055'$history$)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1055 catalog history exclusion';
    END IF;
    original := replace(original,$history$AND version IS DISTINCT FROM '1055'$history$,
        $history$AND version IS DISTINCT FROM '1055' AND version IS DISTINCT FROM '1056'$history$);
    original := replace(original,anchor,anchor || $replacement$
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1056','V1056__world_fixture_first_open_origin.sql'$replacement$);
    EXECUTE original;
END;
$migration$;
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
