-- The existing World hold finalizer accepts only a fixture-purpose terminal for a fixture row.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    IF p_owner_proof_id IS DISTINCT FROM held.initial_admission_request_id$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_finalize_canonical_initial_admission_hold(uuid,uuid,text,text,text,text,bigint,timestamptz,boolean,bytea,text)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact World canonical hold finalizer proof boundary';
    END IF;
    EXECUTE replace(original,anchor,$replacement$    IF association_row.canonical_authority_kind = 'RUN_OWNED_FIXTURE' THEN
        IF p_status IS DISTINCT FROM 'COMMITTED'
            OR held.initial_admission_origin IS DISTINCT FROM 'NO_PRIOR_POINTER'
            OR get_byte(p_canonical_owner_proof_bytes, 0) <> 123
            OR NOT EXISTS (
                SELECT 1 FROM "${serviceSchema}".world_run_owned_fixture_activation activation
                WHERE activation.world_instance_id = instance_row.id
                    AND activation.canonical_game_instance_id = held.canonical_game_instance_id
                    AND activation.fixture_binding_sha256 = instance_row.run_owned_fixture_binding_sha256
                    AND activation.expected_lifecycle_epoch = association_row.preparing_lifecycle_epoch
                    AND activation.result_lifecycle_epoch = held.active_lifecycle_epoch
                    AND activation.result_row_version = instance_row.row_version) THEN
            RAISE EXCEPTION 'World fixture hold requires its original activation and committed terminal'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM (SELECT convert_from(p_canonical_owner_proof_bytes,'UTF8')::JSONB AS body) frame
            WHERE frame.body - 'terminalAt' = jsonb_build_object(
                'schema','world-run-owned-fixture-admission-terminal/v1',
                'authorityKind','RUN_OWNED_FIXTURE',
                'fixturePolicySealEvidenceDigest',association_row.fixture_provenance_digest,
                'selectorBytesBase64',replace(encode(held.canonical_request_bytes,'base64'),E'\n',''),
                'holdId',held.hold_id::TEXT,'holdFence',held.hold_fence::TEXT,
                'outcome',p_status,'pointerVersion',p_owner_pointer_version::TEXT,
                'auditEventId',p_owner_pointer_audit_id,'proofDigest','sha256:' || p_owner_proof_digest)
            AND (frame.body->>'terminalAt')::TIMESTAMPTZ = p_terminal_at) THEN
            RAISE EXCEPTION 'World fixture terminal differs from its exact hold or provenance'
                USING ERRCODE = '23514';
        END IF;
    END IF;

$replacement$ || anchor);
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$AND script='V1076__world_fixture_activation_execution_manifest.sql' AND success);$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_installed()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1076 catalog installation guard';
    END IF;
    EXECUTE replace(original,anchor,$replacement$AND script='V1076__world_fixture_activation_execution_manifest.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1077'
        AND script='V1077__world_fixture_initial_admission_terminal.sql' AND success);$replacement$);
END;
$migration$;

DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$SELECT 'MIGRATION','catalog-installation:1076','V1076__world_fixture_activation_execution_manifest.sql'$anchor$;
    history TEXT := $history$AND version IS DISTINCT FROM '1076'$history$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_bytes(bigint)'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1
        OR (length(original)-length(replace(original,history,'')))/length(history)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1076 catalog migration fact and history exclusion';
    END IF;
    original := replace(original,history,history || $replacement$ AND version IS DISTINCT FROM '1077'$replacement$);
    original := replace(original,anchor,anchor || $replacement$
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1077','V1077__world_fixture_initial_admission_terminal.sql'$replacement$);
    EXECUTE original;
END;
$migration$;
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
