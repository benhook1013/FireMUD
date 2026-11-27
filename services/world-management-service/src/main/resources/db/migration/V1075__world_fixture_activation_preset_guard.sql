-- Recheck the committed fixed preset before accepting a fresh fixture activation receipt.
-- [jooq ignore start]
-- The preparation entry point keeps its PREPARING requirement. The readback path may
-- also verify an ACTIVE row with the exact original activation receipt.
DO $migration$
DECLARE
    original TEXT;
    signature TEXT := $signature$p_private_game_instance_key bigint)$signature$;
    lifecycle TEXT := $lifecycle$OR instance.status IS DISTINCT FROM 'PREPARING'
        OR instance.lifecycle_epoch IS DISTINCT FROM association.preparing_lifecycle_epoch$lifecycle$;
    invocation TEXT := $invocation$p, p_expected_content_digest, p_world_instance_id, p_private_game_instance_key);$invocation$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_verify_run_owned_fixed_preset_plan(jsonb,text,bigint,bigint)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,signature,'')))/length(signature)<>1
        OR (length(original)-length(replace(original,lifecycle,'')))/length(lifecycle)<>1 THEN
        RAISE EXCEPTION 'Expected exact fixed-preset verifier signature and PREPARING guard';
    END IF;
    original := replace(original,signature,$replacement$p_private_game_instance_key bigint, p_allow_activated_read boolean)$replacement$);
    EXECUTE replace(original,lifecycle,$replacement$OR NOT (
            (instance.status IS NOT DISTINCT FROM 'PREPARING'
                AND instance.lifecycle_epoch IS NOT DISTINCT FROM association.preparing_lifecycle_epoch)
            OR (p_allow_activated_read IS TRUE AND instance.status IS NOT DISTINCT FROM 'ACTIVE'
                AND EXISTS (
                    SELECT 1 FROM "${serviceSchema}".world_run_owned_fixture_activation activation
                    WHERE activation.world_instance_id = instance.id
                        AND activation.canonical_game_instance_id = association.canonical_game_instance_id
                        AND activation.fixture_binding_sha256 = instance.run_owned_fixture_binding_sha256
                        AND activation.expected_lifecycle_epoch = association.preparing_lifecycle_epoch
                        AND activation.result_lifecycle_epoch = instance.lifecycle_epoch
                        AND activation.result_row_version = instance.row_version)))$replacement$);

    SELECT pg_get_functiondef('"${serviceSchema}".world_read_run_owned_fixed_preset(text,text,bigint,bigint)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,invocation,'')))/length(invocation)<>1 THEN
        RAISE EXCEPTION 'Expected exact fixed-preset readback verifier call';
    END IF;
    EXECUTE replace(original,invocation,$replacement$p, p_expected_content_digest,
        p_world_instance_id, p_private_game_instance_key, true);$replacement$);
END;
$migration$;
REVOKE ALL ON FUNCTION world_verify_run_owned_fixed_preset_plan(JSONB, TEXT, BIGINT, BIGINT, BOOLEAN)
    FROM PUBLIC;

CREATE OR REPLACE FUNCTION world_verify_run_owned_fixed_preset_plan(
    p JSONB, p_expected_content_digest TEXT, p_world_instance_id BIGINT,
    p_private_game_instance_key BIGINT
) RETURNS VOID
LANGUAGE SQL SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
    SELECT "${serviceSchema}".world_verify_run_owned_fixed_preset_plan(
        p, p_expected_content_digest, p_world_instance_id, p_private_game_instance_key, false);
$$;

CREATE FUNCTION world_require_run_owned_fixture_activation_preset(
    p_world_instance_id BIGINT, p_canonical_game_instance_id UUID, p_fixture_binding_sha256 TEXT
)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    prepared "${serviceSchema}".world_canonical_instance_preparation%ROWTYPE;
BEGIN
    SELECT preparation.* INTO prepared
    FROM "${serviceSchema}".world_canonical_instance_preparation preparation
    WHERE preparation.canonical_game_instance_id = p_canonical_game_instance_id
        AND preparation.world_instance_id = p_world_instance_id
        AND preparation.fixed_preset_content_digest IS NOT NULL
        AND preparation.input_json::JSONB->>'lifecycleBindingDigest' =
            'sha256:' || p_fixture_binding_sha256;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Run-owned fixture activation requires its exact retained preset'
            USING ERRCODE = '23514';
    END IF;
    PERFORM * FROM "${serviceSchema}".world_read_run_owned_fixed_preset(
        prepared.input_json, prepared.fixed_preset_content_digest,
        p_world_instance_id, prepared.private_game_instance_key);
END;
$$;
REVOKE ALL ON FUNCTION world_require_run_owned_fixture_activation_preset(BIGINT, UUID, TEXT)
    FROM PUBLIC;

CREATE FUNCTION world_guard_run_owned_fixture_activation_preset()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    PERFORM "${serviceSchema}".world_require_run_owned_fixture_activation_preset(
        NEW.world_instance_id, NEW.canonical_game_instance_id, NEW.fixture_binding_sha256);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_guard_run_owned_fixture_activation_preset() FROM PUBLIC;
CREATE TRIGGER trg_world_run_owned_fixture_activation_preset
    BEFORE INSERT ON world_run_owned_fixture_activation
    FOR EACH ROW EXECUTE FUNCTION world_guard_run_owned_fixture_activation_preset();

-- Keep the installed-migration check and latest catalog seal on the same frontier.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$AND script='V1074__world_fixture_activation_commit_guard.sql' AND success);$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_installed()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1074 catalog installation guard';
    END IF;
    EXECUTE replace(original,anchor,$replacement$AND script='V1074__world_fixture_activation_commit_guard.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1075'
        AND script='V1075__world_fixture_activation_preset_guard.sql' AND success);$replacement$);
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$SELECT 'MIGRATION','catalog-installation:1074','V1074__world_fixture_activation_commit_guard.sql'$anchor$;
    history TEXT := $history$AND version IS DISTINCT FROM '1074'$history$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_bytes(bigint)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1
        OR (length(original)-length(replace(original,history,'')))/length(history)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1074 catalog migration fact and history exclusion';
    END IF;
    original := replace(original,history,history || $replacement$ AND version IS DISTINCT FROM '1075'$replacement$);
    original := replace(original,anchor,anchor || $replacement$
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1075','V1075__world_fixture_activation_preset_guard.sql'$replacement$);
    EXECUTE original;
END;
$migration$;
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
