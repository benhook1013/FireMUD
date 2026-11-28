-- A fresh activation receipt must commit with its exact World transition.
-- The existing lifecycle and tenant guards still authorize the CAS itself.
-- [jooq ignore start]
CREATE FUNCTION world_require_run_owned_fixture_activation_commit()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_instance w
        WHERE w.id = NEW.world_instance_id
            AND w.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
            AND w.canonical_game_instance_id = NEW.canonical_game_instance_id
            AND w.run_owned_fixture_binding_sha256 = NEW.fixture_binding_sha256
            AND w.run_owned_fixture_preparing_lifecycle_epoch = NEW.expected_lifecycle_epoch
            AND w.status = 'ACTIVE'
            AND w.lifecycle_epoch = NEW.result_lifecycle_epoch
            AND w.row_version = NEW.result_row_version
            AND w.xmin::TEXT = (txid_current() % 4294967296)::TEXT) THEN
        RAISE EXCEPTION 'Run-owned fixture activation receipt did not commit its exact ACTIVE CAS'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_require_run_owned_fixture_activation_commit() FROM PUBLIC;

CREATE CONSTRAINT TRIGGER trg_world_run_owned_fixture_activation_commit
    AFTER INSERT ON world_run_owned_fixture_activation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION world_require_run_owned_fixture_activation_commit();

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
        AND script='V1073__world_fixture_first_open_origin.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1074'
        AND script='V1074__world_fixture_activation_commit_guard.sql' AND success);
$$;

-- Extend the installer fact/exclusion, then append a new immutable catalog epoch.
DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$SELECT 'MIGRATION','catalog-installation:1073','V1073__world_fixture_first_open_origin.sql'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_bytes(bigint)'::REGPROCEDURE) INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1073 catalog migration fact';
    END IF;
    IF (length(original)-length(replace(original,$history$AND version IS DISTINCT FROM '1073'$history$,'')))
        /length($history$AND version IS DISTINCT FROM '1073'$history$)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1073 catalog history exclusion';
    END IF;
    original := replace(original,$history$AND version IS DISTINCT FROM '1073'$history$,
        $history$AND version IS DISTINCT FROM '1073' AND version IS DISTINCT FROM '1074'$history$);
    original := replace(original,anchor,anchor || $replacement$
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1074','V1074__world_fixture_activation_commit_guard.sql'$replacement$);
    EXECUTE original;
END;
$migration$;
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
