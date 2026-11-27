-- Authorize exactly one World row change for each new fixture activation receipt.
-- [jooq ignore start]
CREATE TABLE world_run_owned_fixture_activation_manifest (
    transaction_id BIGINT NOT NULL,
    world_instance_id BIGINT NOT NULL REFERENCES world_run_owned_fixture_activation(world_instance_id)
        DEFERRABLE INITIALLY DEFERRED,
    expected_old JSONB NOT NULL CHECK (jsonb_typeof(expected_old) = 'object'),
    expected_new JSONB NOT NULL CHECK (jsonb_typeof(expected_new) = 'object'),
    PRIMARY KEY (transaction_id, world_instance_id)
);
REVOKE ALL ON world_run_owned_fixture_activation_manifest FROM PUBLIC;

INSERT INTO world_runtime_family_classification(family_id,relation_name,family_role,state_class,fresh_disposition)
VALUES ('world_run_owned_fixture_activation_manifest','world_run_owned_fixture_activation_manifest',
    'CONTROL','NOT_PAYLOAD','CONTROL_ONLY');

CREATE OR REPLACE FUNCTION world_guard_run_owned_fixture_activation_preset()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    original JSONB;
BEGIN
    SELECT to_jsonb(w) INTO original
    FROM "${serviceSchema}".world_instance w
    WHERE w.id = NEW.world_instance_id
        AND w.canonical_authority_kind = 'RUN_OWNED_FIXTURE'
        AND w.canonical_game_instance_id = NEW.canonical_game_instance_id
        AND w.run_owned_fixture_binding_sha256 = NEW.fixture_binding_sha256
        AND w.status = 'PREPARING'
        AND w.lifecycle_epoch = NEW.expected_lifecycle_epoch
        AND w.row_version = NEW.expected_row_version
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Run-owned fixture activation requires its original PREPARING row'
            USING ERRCODE = '23514';
    END IF;
    PERFORM "${serviceSchema}".world_require_run_owned_fixture_activation_preset(
        NEW.world_instance_id, NEW.canonical_game_instance_id, NEW.fixture_binding_sha256);
    INSERT INTO "${serviceSchema}".world_run_owned_fixture_activation_manifest
        (transaction_id,world_instance_id,expected_old,expected_new)
    VALUES (txid_current(),NEW.world_instance_id,original,original || jsonb_build_object(
        'status','ACTIVE','lifecycle_epoch',NEW.result_lifecycle_epoch,'row_version',NEW.result_row_version));
    RETURN NEW;
END;
$$;

CREATE FUNCTION world_consume_run_owned_fixture_activation_manifest(
    p_world_instance_id BIGINT, p_expected_old JSONB, p_expected_new JSONB
) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    DELETE FROM "${serviceSchema}".world_run_owned_fixture_activation_manifest m
    WHERE m.transaction_id = txid_current() AND m.world_instance_id = p_world_instance_id
        AND m.expected_old = p_expected_old AND m.expected_new = p_expected_new;
    RETURN FOUND;
END;
$$;
REVOKE ALL ON FUNCTION world_consume_run_owned_fixture_activation_manifest(BIGINT, JSONB, JSONB)
    FROM PUBLIC;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$AND a.result_row_version = NEW.row_version) THEN$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_guard_canonical_activation_transition()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exactly one fixture activation transition receipt guard';
    END IF;
    EXECUTE replace(original,anchor,$replacement$AND a.result_row_version = NEW.row_version)
            AND "${serviceSchema}".world_consume_run_owned_fixture_activation_manifest(
                OLD.id,to_jsonb(OLD),to_jsonb(NEW)) THEN$replacement$);
END;
$migration$;

CREATE FUNCTION world_reject_unconsumed_run_owned_fixture_activation_manifest()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_run_owned_fixture_activation_manifest m
        WHERE m.transaction_id = NEW.transaction_id AND m.world_instance_id = NEW.world_instance_id) THEN
        RAISE EXCEPTION 'Run-owned fixture activation receipt did not commit its exact ACTIVE CAS'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_reject_unconsumed_run_owned_fixture_activation_manifest() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_world_fixture_activation_manifest_consumed
    AFTER INSERT ON world_run_owned_fixture_activation_manifest
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION world_reject_unconsumed_run_owned_fixture_activation_manifest();

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$AND script='V1075__world_fixture_activation_preset_guard.sql' AND success);$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_installed()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1075 catalog installation guard';
    END IF;
    EXECUTE replace(original,anchor,$replacement$AND script='V1075__world_fixture_activation_preset_guard.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1076'
        AND script='V1076__world_fixture_activation_execution_manifest.sql' AND success);$replacement$);
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$SELECT 'MIGRATION','catalog-installation:1075','V1075__world_fixture_activation_preset_guard.sql'$anchor$;
    history TEXT := $history$AND version IS DISTINCT FROM '1075'$history$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_bytes(bigint)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1
        OR (length(original)-length(replace(original,history,'')))/length(history)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1075 catalog migration fact and history exclusion';
    END IF;
    original := replace(original,history,history || $replacement$ AND version IS DISTINCT FROM '1076'$replacement$);
    original := replace(original,anchor,anchor || $replacement$
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1076','V1076__world_fixture_activation_execution_manifest.sql'$replacement$);
    EXECUTE original;
END;
$migration$;
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
