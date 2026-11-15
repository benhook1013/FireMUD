-- Fixture holds share the existing termination-blocking ledger, but never Human lifecycle proof.
ALTER TABLE initial_admission_bind_hold
    ADD COLUMN fixture_activation_request_id UUID,
    ADD COLUMN fixture_hold_request_bytes BYTEA,
    ADD CONSTRAINT fk_fixture_hold_activation FOREIGN KEY (fixture_activation_request_id)
        REFERENCES world_run_owned_fixture_activation_operation(activation_request_id),
    ADD CONSTRAINT ck_fixture_hold_frame CHECK (
        (fixture_activation_request_id IS NULL AND fixture_hold_request_bytes IS NULL)
        OR (fixture_activation_request_id IS NOT NULL AND fixture_hold_request_bytes IS NOT NULL
            AND canonical_request_bytes IS NOT NULL AND octet_length(fixture_hold_request_bytes)>0));

-- [jooq ignore start]
CREATE FUNCTION world_fixture_hold_binding_valid(h initial_admission_bind_hold)
RETURNS BOOLEAN LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path=pg_catalog,"${serviceSchema}"
AS $$
DECLARE a "${serviceSchema}".world_canonical_instance_association%ROWTYPE;
        r "${serviceSchema}".world_run_owned_fixture_activation_operation%ROWTYPE;
        expected JSONB;
BEGIN
    IF h.fixture_activation_request_id IS NULL OR h.fixture_hold_request_bytes IS NULL
        OR h.canonical_request_bytes IS NULL OR h.active_lifecycle_epoch IS DISTINCT FROM 2 THEN
        RETURN FALSE;
    END IF;
    SELECT * INTO a FROM "${serviceSchema}".world_canonical_instance_association
    WHERE canonical_game_instance_id=h.canonical_game_instance_id;
    IF NOT FOUND OR a.schema_version IS DISTINCT FROM 3
        OR a.canonical_authority_kind IS DISTINCT FROM 'RUN_OWNED_FIXTURE'
        OR a.canonical_tenant_id IS DISTINCT FROM h.canonical_tenant_id
        OR a.canonical_realm_id IS DISTINCT FROM h.realm_uuid
        OR a.catalog_revision IS DISTINCT FROM h.expected_catalog_revision
        OR a.local_tenant_key IS DISTINCT FROM h.tenant_id
        OR a.private_game_instance_key IS DISTINCT FROM h.game_instance_id
        OR a.local_version_key IS DISTINCT FROM h.version_id THEN RETURN FALSE; END IF;
    SELECT * INTO r FROM "${serviceSchema}".world_run_owned_fixture_activation_operation
    WHERE activation_request_id=h.fixture_activation_request_id;
    IF NOT FOUND OR r.canonical_game_instance_id IS DISTINCT FROM h.canonical_game_instance_id
        OR r.outcome IS DISTINCT FROM 'COMMITTED'
        OR r.expected_lifecycle_epoch IS DISTINCT FROM 1 OR r.expected_row_version IS DISTINCT FROM 0
        OR r.result_lifecycle_epoch IS DISTINCT FROM 2 OR r.result_row_version IS DISTINCT FROM 1
        OR (convert_from(r.request_bytes,'UTF8')::JSONB)->'request'->>'fixturePolicySealEvidenceDigest'
            IS DISTINCT FROM a.fixture_provenance_digest THEN RETURN FALSE; END IF;
    expected := jsonb_build_object('schema','world-fixture-initial-admission-hold-request/v1',
        'authorityKind','RUN_OWNED_FIXTURE','fixturePolicySealEvidenceDigest',a.fixture_provenance_digest,
        'selectorBytesBase64',replace(encode(h.canonical_request_bytes,'base64'),E'\n',''),
        'activationRequestBytesBase64',replace(encode(r.request_bytes,'base64'),E'\n',''));
    RETURN convert_from(h.fixture_hold_request_bytes,'UTF8')::JSONB = expected;
END;
$$;
REVOKE ALL ON FUNCTION world_fixture_hold_binding_valid(initial_admission_bind_hold) FROM PUBLIC;

DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$BEGIN
    IF TG_OP = 'INSERT' AND EXISTS ($anchor$;
    denial TEXT := $anchor$        RAISE EXCEPTION 'Run-owned fixture initial-admission holds are disabled'
            USING ERRCODE = '23514';$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_guard_canonical_initial_admission_hold()'::REGPROCEDURE) INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1
        OR (length(original)-length(replace(original,denial,'')))/length(denial)<>1 THEN
        RAISE EXCEPTION 'Expected exact fixture hold guard anchors';
    END IF;
    original := replace(original,anchor,$replacement$BEGIN
    IF TG_OP='UPDATE' AND (OLD.fixture_activation_request_id IS DISTINCT FROM NEW.fixture_activation_request_id
        OR OLD.fixture_hold_request_bytes IS DISTINCT FROM NEW.fixture_hold_request_bytes) THEN
        RAISE EXCEPTION 'Fixture hold authority is immutable' USING ERRCODE='55000';
    END IF;
    IF TG_OP='INSERT' AND (NEW.fixture_activation_request_id IS NOT NULL OR NEW.fixture_hold_request_bytes IS NOT NULL)
        AND NOT "${serviceSchema}".world_fixture_hold_binding_valid(NEW) THEN
        RAISE EXCEPTION 'Fixture hold lacks its exact committed activation receipt' USING ERRCODE='23514';
    END IF;
    IF TG_OP = 'INSERT' AND EXISTS ($replacement$);
    original := replace(original,denial,$replacement$        IF NOT "${serviceSchema}".world_fixture_hold_binding_valid(NEW) THEN
            RAISE EXCEPTION 'Run-owned fixture initial-admission holds require exact fixture authority'
                USING ERRCODE = '23514';
        END IF;$replacement$);
    EXECUTE original;
END;
$migration$;

DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$        RAISE EXCEPTION 'Run-owned fixture initial-admission hold finalization is disabled'
            USING ERRCODE = '23514';$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_finalize_canonical_initial_admission_hold(uuid,uuid,text,text,text,text,bigint,timestamptz,boolean,bytea,text)'::REGPROCEDURE) INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact fixture hold finalizer anchor';
    END IF;
    original := replace(original,anchor,$replacement$        IF NOT "${serviceSchema}".world_fixture_hold_binding_valid(candidate)
            OR (convert_from(p_canonical_owner_proof_bytes,'UTF8')::JSONB - 'terminalAt') IS DISTINCT FROM
                jsonb_build_object('schema','world-fixture-initial-admission-terminal/v1',
                    'requestBytesBase64',replace(encode(candidate.fixture_hold_request_bytes,'base64'),E'\n',''),
                    'holdId',p_hold_id::TEXT,'holdFence',p_hold_fence::TEXT,'outcome',p_status,
                    'pointerVersion',p_owner_pointer_version::TEXT,'auditEventId',p_owner_pointer_audit_id,
                    'positiveDurableAbort',p_positive_durable_abort)
            OR (convert_from(p_canonical_owner_proof_bytes,'UTF8')::JSONB->>'terminalAt') IS NULL
            OR (convert_from(p_canonical_owner_proof_bytes,'UTF8')::JSONB->>'terminalAt')::TIMESTAMPTZ IS DISTINCT FROM p_terminal_at THEN
            RAISE EXCEPTION 'Fixture hold terminal proof differs from exact authority and outcome'
                USING ERRCODE = '23514';
        END IF;$replacement$);
    EXECUTE original;
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
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1051'
        AND script='V1051__world_run_owned_fixture_activation.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1052'
        AND script='V1052__world_fixture_activation_catalog.sql' AND success)
    AND EXISTS (SELECT 1 FROM "${flyway:table}" WHERE version='1053'
        AND script='V1053__world_fixture_initial_hold.sql' AND success);
$$;

-- Extend the canonical installed-migration fact set without rewriting historical epochs.
DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$SELECT 'MIGRATION','catalog-installation:1052','V1052__world_fixture_activation_catalog.sql'$anchor$;
BEGIN
    SELECT pg_get_functiondef('"${serviceSchema}".world_runtime_family_catalog_bytes(bigint)'::REGPROCEDURE) INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1052 catalog migration fact';
    END IF;
    IF (length(original)-length(replace(original,$history$AND version IS DISTINCT FROM '1052'$history$,'')))
        /length($history$AND version IS DISTINCT FROM '1052'$history$)<>1 THEN
        RAISE EXCEPTION 'Expected exact V1052 catalog history exclusion';
    END IF;
    original := replace(original,$history$AND version IS DISTINCT FROM '1052'$history$,
        $history$AND version IS DISTINCT FROM '1052' AND version IS DISTINCT FROM '1053'$history$);
    original := replace(original,anchor,anchor || $replacement$
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1053','V1053__world_fixture_initial_hold.sql'$replacement$);
    EXECUTE original;
END;
$migration$;
-- The existing hold family remains runtime. Seal a new catalog epoch for the added columns/guards.
INSERT INTO world_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT next_epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS next_epoch FROM world_runtime_family_catalog_epoch) next
CROSS JOIN LATERAL (SELECT world_runtime_family_catalog_bytes(next.next_epoch) AS bytes) seal;
-- [jooq ignore stop]
