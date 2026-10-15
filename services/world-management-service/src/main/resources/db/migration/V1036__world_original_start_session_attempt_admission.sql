-- Fresh World execution intents retain Account's exact first original-attempt observation.
-- Existing V62 rows remain untouched and nullable; missing evidence never becomes migration data.
ALTER TABLE world_canonical_instance_execution
    ADD COLUMN game_session_owner_mutation_id UUID,
    ADD COLUMN original_attempt_read_request_id UUID,
    ADD COLUMN original_game_session_lease_expires_at TIMESTAMPTZ,
    ADD COLUMN original_attempt_evidence_bytes BYTEA,
    ADD COLUMN original_attempt_evidence_sha256 VARCHAR(71);

ALTER TABLE world_canonical_instance_execution
    ADD CONSTRAINT ck_world_execution_original_attempt_witness
    CHECK (
        (game_session_owner_mutation_id IS NULL
            AND original_attempt_read_request_id IS NULL
            AND original_game_session_lease_expires_at IS NULL
            AND original_attempt_evidence_bytes IS NULL
            AND original_attempt_evidence_sha256 IS NULL)
        OR (game_session_owner_mutation_id IS NOT NULL
            AND game_session_owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND original_attempt_read_request_id IS NOT NULL
            AND original_attempt_read_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND original_game_session_lease_expires_at IS NOT NULL
            AND original_attempt_evidence_bytes IS NOT NULL
            AND octet_length(original_attempt_evidence_bytes) BETWEEN 1 AND 525824
            AND original_attempt_evidence_sha256 IS NOT NULL
            AND original_attempt_evidence_sha256 ~ '^sha256:[0-9a-f]{64}$'
            AND original_attempt_evidence_sha256 = 'sha256:' || encode(sha256(original_attempt_evidence_bytes), 'hex'))
    );

-- [jooq ignore start]
REVOKE ALL ON world_canonical_instance_execution FROM PUBLIC;

-- Fresh operations use this function so the Account-returned exact observation is born with the
-- World-local identity and execution fence. A V62 row with absent witness fields is never upgraded.
CREATE FUNCTION world_persist_canonical_instance_execution_intent_with_original_attempt(
    p_input_json TEXT, p_input_digest TEXT, p_original_tuple BYTEA,
    p_account_participation_id UUID, p_account_participation_fence BIGINT,
    p_game_session_attempt_id UUID, p_game_session_fence BIGINT,
    p_canonical_game_instance_id UUID,
    p_game_session_mutation_id UUID, p_attempt_read_request_id UUID,
    p_original_game_session_lease_expires_at TIMESTAMPTZ,
    p_original_attempt_evidence_bytes BYTEA, p_original_attempt_evidence_sha256 TEXT
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    input JSONB;
    tuple_value JSONB;
    projection_expiry TIMESTAMPTZ;
    v_target_namespace TEXT;
    v_canonical_tenant_id UUID;
    v_request_id TEXT;
    v_world_slug TEXT;
    v_game_template_id BIGINT;
    prior "${serviceSchema}".world_canonical_instance_execution%ROWTYPE;
BEGIN
    PERFORM "${serviceSchema}".world_validate_canonical_instance_execution_input(
        p_input_json, p_input_digest, p_original_tuple,
        p_account_participation_id, p_account_participation_fence,
        p_game_session_attempt_id, p_game_session_fence, p_canonical_game_instance_id);
    IF p_game_session_mutation_id IS NULL
        OR p_game_session_mutation_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR p_attempt_read_request_id IS NULL
        OR p_attempt_read_request_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR p_original_game_session_lease_expires_at IS NULL
        OR p_original_attempt_evidence_bytes IS NULL
        OR octet_length(p_original_attempt_evidence_bytes) NOT BETWEEN 1 AND 525824
        OR p_original_attempt_evidence_sha256 IS DISTINCT FROM
            'sha256:' || encode(sha256(p_original_attempt_evidence_bytes), 'hex') THEN
        RAISE EXCEPTION 'Exact Account original Game Session attempt observation is required'
            USING ERRCODE = '23514';
    END IF;

    input := p_input_json::JSONB;
    tuple_value := convert_from(p_original_tuple, 'UTF8')::JSONB;
    v_target_namespace := input->'identity'->>'targetNamespace';
    v_canonical_tenant_id := (input->'identity'->>'canonicalTenantId')::UUID;
    v_request_id := input->'identity'->>'controlPlaneRequestId';
    v_world_slug := input->'identity'->>'worldSlug';
    v_game_template_id := (((input->'gameSessionReadEvidence'->>'descriptorJson')::JSONB)->>'gameTemplateId')::BIGINT;
    projection_expiry := (tuple_value->'authorityEvidenceBundle'->'accountProjectionEvidence'->>'expiresAt')::TIMESTAMPTZ;

    PERFORM "${serviceSchema}".world_lock_canonical_instance_execution(
        v_target_namespace, v_canonical_tenant_id, v_request_id, p_canonical_game_instance_id);
    -- Preliminary checks bound the retained intent. Actual admission repeats both expiries only
    -- after all V35 owner locks, so a lock wait cannot admit a stale lease.
    IF projection_expiry <= clock_timestamp()
        OR p_original_game_session_lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'Original StartSession authorization or Game Session lease expired before World intent retention'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO prior FROM "${serviceSchema}".world_canonical_instance_execution e
    WHERE e.target_namespace = v_target_namespace
        AND e.canonical_tenant_id = v_canonical_tenant_id
        AND e.control_plane_request_id = v_request_id
    FOR UPDATE;
    IF FOUND THEN
        IF prior.canonical_game_instance_id IS DISTINCT FROM p_canonical_game_instance_id
            OR prior.canonical_world_slug IS DISTINCT FROM v_world_slug
            OR prior.game_template_id IS DISTINCT FROM v_game_template_id
            OR prior.original_post_authorization_tuple IS DISTINCT FROM p_original_tuple
            OR prior.account_world_participation_id IS DISTINCT FROM p_account_participation_id
            OR prior.account_world_participation_fence IS DISTINCT FROM p_account_participation_fence
            OR prior.game_session_owner_attempt_id IS DISTINCT FROM p_game_session_attempt_id
            OR prior.game_session_owner_fence IS DISTINCT FROM p_game_session_fence
            OR prior.game_session_owner_mutation_id IS DISTINCT FROM p_game_session_mutation_id
            OR prior.original_attempt_read_request_id IS DISTINCT FROM p_attempt_read_request_id
            OR prior.original_game_session_lease_expires_at IS DISTINCT FROM p_original_game_session_lease_expires_at
            OR prior.original_attempt_evidence_bytes IS DISTINCT FROM p_original_attempt_evidence_bytes
            OR prior.original_attempt_evidence_sha256 IS DISTINCT FROM p_original_attempt_evidence_sha256
            OR prior.preparation_input_digest IS DISTINCT FROM p_input_digest
            OR prior.preparation_input_json IS DISTINCT FROM p_input_json THEN
            RAISE EXCEPTION 'World execution request is already bound to a different or incomplete original owner observation'
                USING ERRCODE = '23505';
        END IF;
        RETURN 0;
    END IF;

    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation p
        WHERE p.canonical_game_instance_id = p_canonical_game_instance_id
            OR (p.target_namespace = v_target_namespace
                AND p.canonical_tenant_id = v_canonical_tenant_id
                AND p.control_plane_request_id = v_request_id))
        OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_association a
            WHERE a.canonical_game_instance_id = p_canonical_game_instance_id
                OR (a.canonical_target_namespace = v_target_namespace
                    AND a.canonical_tenant_id = v_canonical_tenant_id
                    AND a.control_plane_request_id = v_request_id)) THEN
        RAISE EXCEPTION 'Historical World materialization has no exact execution identity'
            USING ERRCODE = '23514';
    END IF;

    INSERT INTO "${serviceSchema}".world_canonical_instance_execution (
        target_namespace, canonical_tenant_id, control_plane_request_id,
        canonical_game_instance_id, canonical_world_slug, game_template_id,
        original_post_authorization_tuple, original_post_authorization_tuple_sha256,
        account_world_participation_id, account_world_participation_fence,
        game_session_owner_attempt_id, game_session_owner_fence,
        game_session_owner_mutation_id, original_attempt_read_request_id,
        original_game_session_lease_expires_at, original_attempt_evidence_bytes,
        original_attempt_evidence_sha256,
        preparation_input_digest, preparation_input_json, world_execution_fence,
        operation_state)
    VALUES (
        v_target_namespace, v_canonical_tenant_id, v_request_id,
        p_canonical_game_instance_id, v_world_slug, v_game_template_id,
        p_original_tuple, encode(sha256(p_original_tuple), 'hex'),
        p_account_participation_id, p_account_participation_fence,
        p_game_session_attempt_id, p_game_session_fence,
        p_game_session_mutation_id, p_attempt_read_request_id,
        p_original_game_session_lease_expires_at, p_original_attempt_evidence_bytes,
        p_original_attempt_evidence_sha256,
        p_input_digest, p_input_json,
        nextval('"${serviceSchema}".world_canonical_instance_execution_fence_seq'),
        'PENDING')
    RETURNING world_execution_fence INTO prior.world_execution_fence;
    RETURN prior.world_execution_fence;
END;
$$;
REVOKE ALL ON FUNCTION world_persist_canonical_instance_execution_intent_with_original_attempt(
    TEXT, TEXT, BYTEA, UUID, BIGINT, UUID, BIGINT, UUID, UUID, UUID, TIMESTAMPTZ, BYTEA, TEXT) FROM PUBLIC;

-- Fresh claims and execution admission compare the full Account observation, not just the older
-- V62 tuple/participation/attempt projection.
CREATE FUNCTION world_claim_canonical_instance_execution_with_original_attempt(
    p_input_json TEXT, p_input_digest TEXT, p_original_tuple BYTEA,
    p_account_participation_id UUID, p_account_participation_fence BIGINT,
    p_game_session_attempt_id UUID, p_game_session_fence BIGINT,
    p_canonical_game_instance_id UUID,
    p_game_session_mutation_id UUID, p_attempt_read_request_id UUID,
    p_original_game_session_lease_expires_at TIMESTAMPTZ,
    p_original_attempt_evidence_bytes BYTEA, p_original_attempt_evidence_sha256 TEXT
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    result_fence BIGINT;
    identity JSONB := p_input_json::JSONB->'identity';
    operation "${serviceSchema}".world_canonical_instance_execution%ROWTYPE;
BEGIN
    result_fence := "${serviceSchema}".world_claim_canonical_instance_execution(
        p_input_json, p_input_digest, p_original_tuple,
        p_account_participation_id, p_account_participation_fence,
        p_game_session_attempt_id, p_game_session_fence, p_canonical_game_instance_id);
    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e
    WHERE e.target_namespace = identity->>'targetNamespace'
        AND e.canonical_tenant_id = (identity->>'canonicalTenantId')::UUID
        AND e.control_plane_request_id = identity->>'controlPlaneRequestId'
    FOR UPDATE;
    IF NOT FOUND
        OR operation.game_session_owner_mutation_id IS DISTINCT FROM p_game_session_mutation_id
        OR operation.original_attempt_read_request_id IS DISTINCT FROM p_attempt_read_request_id
        OR operation.original_game_session_lease_expires_at IS DISTINCT FROM p_original_game_session_lease_expires_at
        OR operation.original_attempt_evidence_bytes IS DISTINCT FROM p_original_attempt_evidence_bytes
        OR operation.original_attempt_evidence_sha256 IS DISTINCT FROM p_original_attempt_evidence_sha256 THEN
        RAISE EXCEPTION 'World execution claim differs from the exact Account owner observation'
            USING ERRCODE = '23514';
    END IF;
    RETURN result_fence;
END;
$$;
REVOKE ALL ON FUNCTION world_claim_canonical_instance_execution_with_original_attempt(
    TEXT, TEXT, BYTEA, UUID, BIGINT, UUID, BIGINT, UUID, UUID, UUID, TIMESTAMPTZ, BYTEA, TEXT) FROM PUBLIC;

CREATE FUNCTION world_begin_canonical_instance_execution_with_original_attempt(
    p_input_json TEXT, p_input_digest TEXT, p_original_tuple BYTEA,
    p_account_participation_id UUID, p_account_participation_fence BIGINT,
    p_game_session_attempt_id UUID, p_game_session_fence BIGINT,
    p_canonical_game_instance_id UUID, p_world_execution_fence BIGINT,
    p_game_session_mutation_id UUID, p_attempt_read_request_id UUID,
    p_original_game_session_lease_expires_at TIMESTAMPTZ,
    p_original_attempt_evidence_bytes BYTEA, p_original_attempt_evidence_sha256 TEXT
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    result_fence BIGINT;
    identity JSONB := p_input_json::JSONB->'identity';
    operation "${serviceSchema}".world_canonical_instance_execution%ROWTYPE;
BEGIN
    result_fence := "${serviceSchema}".world_begin_canonical_instance_execution(
        p_input_json, p_input_digest, p_original_tuple,
        p_account_participation_id, p_account_participation_fence,
        p_game_session_attempt_id, p_game_session_fence,
        p_canonical_game_instance_id, p_world_execution_fence);
    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e
    WHERE e.target_namespace = identity->>'targetNamespace'
        AND e.canonical_tenant_id = (identity->>'canonicalTenantId')::UUID
        AND e.control_plane_request_id = identity->>'controlPlaneRequestId'
    FOR UPDATE;
    IF NOT FOUND
        OR operation.game_session_owner_mutation_id IS DISTINCT FROM p_game_session_mutation_id
        OR operation.original_attempt_read_request_id IS DISTINCT FROM p_attempt_read_request_id
        OR operation.original_game_session_lease_expires_at IS DISTINCT FROM p_original_game_session_lease_expires_at
        OR operation.original_attempt_evidence_bytes IS DISTINCT FROM p_original_attempt_evidence_bytes
        OR operation.original_attempt_evidence_sha256 IS DISTINCT FROM p_original_attempt_evidence_sha256 THEN
        RAISE EXCEPTION 'World execution admission differs from the exact Account owner observation'
            USING ERRCODE = '23514';
    END IF;
    RETURN result_fence;
END;
$$;
REVOKE ALL ON FUNCTION world_begin_canonical_instance_execution_with_original_attempt(
    TEXT, TEXT, BYTEA, UUID, BIGINT, UUID, BIGINT, UUID, BIGINT, UUID, UUID, TIMESTAMPTZ, BYTEA, TEXT) FROM PUBLIC;

-- Exact durable abort uses the same request/target serialization and binds the Account-returned
-- owner observation when it must create the terminal row. It never settles/releases Account itself.
CREATE FUNCTION world_abort_canonical_instance_execution_with_original_attempt(
    p_target_namespace TEXT, p_canonical_tenant_id UUID, p_control_plane_request_id TEXT,
    p_canonical_game_instance_id UUID, p_preparation_input_json TEXT,
    p_preparation_input_digest TEXT, p_original_tuple BYTEA,
    p_account_participation_id UUID, p_account_participation_fence BIGINT,
    p_game_session_attempt_id UUID, p_game_session_fence BIGINT,
    p_game_session_mutation_id UUID, p_attempt_read_request_id UUID,
    p_original_game_session_lease_expires_at TIMESTAMPTZ,
    p_original_attempt_evidence_bytes BYTEA, p_original_attempt_evidence_sha256 TEXT
) RETURNS TABLE(operation_state TEXT, world_execution_fence BIGINT, world_instance_id BIGINT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    operation "${serviceSchema}".world_canonical_instance_execution%ROWTYPE;
    tuple_value JSONB;
BEGIN
    PERFORM "${serviceSchema}".world_validate_canonical_instance_execution_input(
        p_preparation_input_json, p_preparation_input_digest, p_original_tuple,
        p_account_participation_id, p_account_participation_fence,
        p_game_session_attempt_id, p_game_session_fence, p_canonical_game_instance_id);
    IF p_game_session_mutation_id IS NULL
        OR p_game_session_mutation_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR p_attempt_read_request_id IS NULL
        OR p_attempt_read_request_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR p_original_game_session_lease_expires_at IS NULL
        OR p_original_attempt_evidence_bytes IS NULL
        OR octet_length(p_original_attempt_evidence_bytes) NOT BETWEEN 1 AND 525824
        OR p_original_attempt_evidence_sha256 IS DISTINCT FROM
            'sha256:' || encode(sha256(p_original_attempt_evidence_bytes), 'hex') THEN
        RAISE EXCEPTION 'Exact Account original Game Session attempt observation is required for World abort'
            USING ERRCODE = '23514';
    END IF;
    tuple_value := convert_from(p_original_tuple, 'UTF8')::JSONB;
    IF tuple_value->>'controlPlaneRequestId' IS DISTINCT FROM p_control_plane_request_id
        OR tuple_value->'scope'->>'targetNamespace' IS DISTINCT FROM p_target_namespace
        OR tuple_value->'scope'->>'tenantId' IS DISTINCT FROM p_canonical_tenant_id::TEXT THEN
        RAISE EXCEPTION 'World abort identity differs from the original scoped StartSession tuple'
            USING ERRCODE = '23514';
    END IF;
    PERFORM "${serviceSchema}".world_lock_canonical_instance_execution(
        p_target_namespace, p_canonical_tenant_id, p_control_plane_request_id,
        p_canonical_game_instance_id);
    PERFORM set_config('world.canonical_abort_namespace', p_target_namespace, TRUE);
    PERFORM set_config('world.canonical_abort_tenant', p_canonical_tenant_id::TEXT, TRUE);
    PERFORM set_config('world.canonical_abort_request', p_control_plane_request_id, TRUE);
    PERFORM set_config('world.canonical_abort_target', p_canonical_game_instance_id::TEXT, TRUE);
    PERFORM set_config('world.canonical_abort_mutation', p_game_session_mutation_id::TEXT, TRUE);
    PERFORM set_config('world.canonical_abort_attempt_read', p_attempt_read_request_id::TEXT, TRUE);
    PERFORM set_config(
        'world.canonical_abort_lease_expiry',
        p_original_game_session_lease_expires_at::TEXT,
        TRUE);
    PERFORM set_config(
        'world.canonical_abort_observation_digest',
        p_original_attempt_evidence_sha256,
        TRUE);
    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e
    WHERE e.target_namespace = p_target_namespace
        AND e.canonical_tenant_id = p_canonical_tenant_id
        AND e.control_plane_request_id = p_control_plane_request_id
    FOR UPDATE;
    IF NOT FOUND THEN
        IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_execution e
                WHERE e.canonical_game_instance_id = p_canonical_game_instance_id)
            OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation p
                WHERE p.canonical_game_instance_id = p_canonical_game_instance_id
                    OR (p.target_namespace = p_target_namespace
                        AND p.canonical_tenant_id = p_canonical_tenant_id
                        AND p.control_plane_request_id = p_control_plane_request_id))
            OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_association a
                WHERE a.canonical_game_instance_id = p_canonical_game_instance_id
                    OR (a.canonical_target_namespace = p_target_namespace
                        AND a.canonical_tenant_id = p_canonical_tenant_id
                        AND a.control_plane_request_id = p_control_plane_request_id)) THEN
            RAISE EXCEPTION 'Historical or conflicting World rows make missing-operation abort unresolved'
                USING ERRCODE = '23514';
        END IF;
        INSERT INTO "${serviceSchema}".world_canonical_instance_execution (
            target_namespace, canonical_tenant_id, control_plane_request_id,
            canonical_game_instance_id, canonical_world_slug, game_template_id,
            original_post_authorization_tuple, original_post_authorization_tuple_sha256,
            account_world_participation_id, account_world_participation_fence,
            game_session_owner_attempt_id, game_session_owner_fence,
            game_session_owner_mutation_id, original_attempt_read_request_id,
            original_game_session_lease_expires_at, original_attempt_evidence_bytes,
            original_attempt_evidence_sha256,
            preparation_input_digest, preparation_input_json, world_execution_fence,
            operation_state, terminal_at)
        VALUES (
            p_target_namespace, p_canonical_tenant_id, p_control_plane_request_id,
            p_canonical_game_instance_id,
            (p_preparation_input_json::JSONB->'identity'->>'worldSlug'),
            (((p_preparation_input_json::JSONB->'gameSessionReadEvidence'->>'descriptorJson')::JSONB)->>'gameTemplateId')::BIGINT,
            p_original_tuple, encode(sha256(p_original_tuple), 'hex'),
            p_account_participation_id, p_account_participation_fence,
            p_game_session_attempt_id, p_game_session_fence,
            p_game_session_mutation_id, p_attempt_read_request_id,
            p_original_game_session_lease_expires_at, p_original_attempt_evidence_bytes,
            p_original_attempt_evidence_sha256,
            p_preparation_input_digest, p_preparation_input_json,
            nextval('"${serviceSchema}".world_canonical_instance_execution_fence_seq'),
            'ABORTED', clock_timestamp())
        RETURNING * INTO operation;
    END IF;
    IF operation.canonical_game_instance_id IS DISTINCT FROM p_canonical_game_instance_id
        OR operation.canonical_world_slug IS DISTINCT FROM p_preparation_input_json::JSONB->'identity'->>'worldSlug'
        OR operation.game_template_id IS DISTINCT FROM (((p_preparation_input_json::JSONB->'gameSessionReadEvidence'->>'descriptorJson')::JSONB)->>'gameTemplateId')::BIGINT
        OR operation.preparation_input_digest IS DISTINCT FROM p_preparation_input_digest
        OR operation.preparation_input_json IS DISTINCT FROM p_preparation_input_json
        OR operation.original_post_authorization_tuple IS DISTINCT FROM p_original_tuple
        OR operation.account_world_participation_id IS DISTINCT FROM p_account_participation_id
        OR operation.account_world_participation_fence IS DISTINCT FROM p_account_participation_fence
        OR operation.game_session_owner_attempt_id IS DISTINCT FROM p_game_session_attempt_id
        OR operation.game_session_owner_fence IS DISTINCT FROM p_game_session_fence
        OR operation.game_session_owner_mutation_id IS DISTINCT FROM p_game_session_mutation_id
        OR operation.original_attempt_read_request_id IS DISTINCT FROM p_attempt_read_request_id
        OR operation.original_game_session_lease_expires_at IS DISTINCT FROM p_original_game_session_lease_expires_at
        OR operation.original_attempt_evidence_bytes IS DISTINCT FROM p_original_attempt_evidence_bytes
        OR operation.original_attempt_evidence_sha256 IS DISTINCT FROM p_original_attempt_evidence_sha256 THEN
        RAISE EXCEPTION 'World abort differs from the exact original full owner-observation identity'
            USING ERRCODE = '23514';
    END IF;
    IF operation.operation_state = 'PENDING' THEN
        IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation p
                WHERE p.canonical_game_instance_id = operation.canonical_game_instance_id
                    OR (p.target_namespace = operation.target_namespace
                        AND p.canonical_tenant_id = operation.canonical_tenant_id
                        AND p.control_plane_request_id = operation.control_plane_request_id))
            OR EXISTS (SELECT 1 FROM "${serviceSchema}".world_canonical_instance_association a
                WHERE a.canonical_game_instance_id = operation.canonical_game_instance_id
                    OR (a.canonical_target_namespace = operation.target_namespace
                        AND a.canonical_tenant_id = operation.canonical_tenant_id
                        AND a.control_plane_request_id = operation.control_plane_request_id)) THEN
            RAISE EXCEPTION 'World has unbound materialization rows; operation remains unresolved'
                USING ERRCODE = '23514';
        END IF;
        UPDATE "${serviceSchema}".world_canonical_instance_execution e
        SET operation_state = 'ABORTED', terminal_at = clock_timestamp()
        WHERE e.target_namespace = operation.target_namespace
            AND e.canonical_tenant_id = operation.canonical_tenant_id
            AND e.control_plane_request_id = operation.control_plane_request_id;
        operation.operation_state := 'ABORTED';
    ELSIF operation.operation_state NOT IN ('COMMITTED', 'ABORTED') THEN
        RAISE EXCEPTION 'World execution is not in a durable terminal state'
            USING ERRCODE = '23514';
    END IF;
    operation_state := operation.operation_state;
    world_execution_fence := operation.world_execution_fence;
    world_instance_id := operation.world_instance_id;
    RETURN NEXT;
END;
$$;
REVOKE ALL ON FUNCTION world_abort_canonical_instance_execution_with_original_attempt(
    TEXT, UUID, TEXT, UUID, TEXT, TEXT, BYTEA, UUID, BIGINT, UUID, BIGINT,
    UUID, UUID, TIMESTAMPTZ, BYTEA, TEXT) FROM PUBLIC;

-- V35 calls this hook only after its publication, target and request locks. The original Account
-- authorization and unchanged Game Session lease must both still be valid at that local admission.
CREATE OR REPLACE FUNCTION world_require_active_canonical_instance_execution(
    p_input_json TEXT, p_input_digest TEXT
) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    operation "${serviceSchema}".world_canonical_instance_execution%ROWTYPE;
    original_tuple JSONB;
    original_expiry TIMESTAMPTZ;
BEGIN
    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e
    WHERE e.target_namespace = current_setting('world.canonical_execution_namespace', TRUE)
        AND e.canonical_tenant_id::TEXT = current_setting('world.canonical_execution_tenant', TRUE)
        AND e.control_plane_request_id = current_setting('world.canonical_execution_request', TRUE)
        AND e.canonical_game_instance_id::TEXT = current_setting('world.canonical_execution_target', TRUE)
        AND e.world_execution_fence::TEXT = current_setting('world.canonical_execution_fence', TRUE)
        AND e.preparation_input_digest = p_input_digest
        AND e.preparation_input_json = p_input_json
        AND e.operation_state = 'EXECUTING'
        AND e.active_transaction_id = txid_current();
    IF NOT FOUND
        OR operation.game_session_owner_mutation_id IS NULL
        OR operation.original_attempt_read_request_id IS NULL
        OR operation.original_game_session_lease_expires_at IS NULL
        OR operation.original_attempt_evidence_bytes IS NULL
        OR operation.original_attempt_evidence_sha256 IS DISTINCT FROM
            'sha256:' || encode(sha256(operation.original_attempt_evidence_bytes), 'hex') THEN
        RAISE EXCEPTION 'V35 requires the exact current World and Account owner-observation identity'
            USING ERRCODE = '23514';
    END IF;
    original_tuple := convert_from(operation.original_post_authorization_tuple, 'UTF8')::JSONB;
    original_expiry := (original_tuple->'authorityEvidenceBundle'->'accountProjectionEvidence'->>'expiresAt')::TIMESTAMPTZ;
    IF original_expiry <= clock_timestamp()
        OR operation.original_game_session_lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'Original StartSession authorization or Game Session lease expired before World execution admission'
            USING ERRCODE = '23514';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION world_require_active_canonical_instance_execution(TEXT, TEXT) FROM PUBLIC;

-- V62's transition guard already serializes each state with its World fence. Replace only the
-- owner-private guard body to make the returned Account witness immutable and require it for every
-- new PENDING intent/claim/admission. Existing rows keep their original null witness values.
CREATE OR REPLACE FUNCTION world_enforce_canonical_instance_execution_transition()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.target_namespace IS DISTINCT FROM OLD.target_namespace
            OR NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
            OR NEW.control_plane_request_id IS DISTINCT FROM OLD.control_plane_request_id
            OR NEW.canonical_game_instance_id IS DISTINCT FROM OLD.canonical_game_instance_id
            OR NEW.canonical_world_slug IS DISTINCT FROM OLD.canonical_world_slug
            OR NEW.game_template_id IS DISTINCT FROM OLD.game_template_id
            OR NEW.original_post_authorization_tuple IS DISTINCT FROM OLD.original_post_authorization_tuple
            OR NEW.original_post_authorization_tuple_sha256 IS DISTINCT FROM OLD.original_post_authorization_tuple_sha256
            OR NEW.account_world_participation_id IS DISTINCT FROM OLD.account_world_participation_id
            OR NEW.account_world_participation_fence IS DISTINCT FROM OLD.account_world_participation_fence
            OR NEW.game_session_owner_attempt_id IS DISTINCT FROM OLD.game_session_owner_attempt_id
            OR NEW.game_session_owner_fence IS DISTINCT FROM OLD.game_session_owner_fence
            OR NEW.game_session_owner_mutation_id IS DISTINCT FROM OLD.game_session_owner_mutation_id
            OR NEW.original_attempt_read_request_id IS DISTINCT FROM OLD.original_attempt_read_request_id
            OR NEW.original_game_session_lease_expires_at IS DISTINCT FROM OLD.original_game_session_lease_expires_at
            OR NEW.original_attempt_evidence_bytes IS DISTINCT FROM OLD.original_attempt_evidence_bytes
            OR NEW.original_attempt_evidence_sha256 IS DISTINCT FROM OLD.original_attempt_evidence_sha256
            OR NEW.preparation_input_digest IS DISTINCT FROM OLD.preparation_input_digest
            OR NEW.preparation_input_json IS DISTINCT FROM OLD.preparation_input_json
            OR NEW.world_execution_fence IS DISTINCT FROM OLD.world_execution_fence
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'World execution identity and Account owner witness are immutable'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.operation_state = 'PENDING' AND NEW.operation_state = 'PENDING' THEN
            IF OLD.execution_claimed OR NOT NEW.execution_claimed
                OR OLD.active_transaction_id IS DISTINCT FROM NEW.active_transaction_id
                OR OLD.world_instance_id IS DISTINCT FROM NEW.world_instance_id
                OR OLD.terminal_at IS DISTINCT FROM NEW.terminal_at
                OR NEW.game_session_owner_mutation_id IS NULL
                OR NEW.original_attempt_read_request_id IS NULL
                OR NEW.original_game_session_lease_expires_at IS NULL
                OR NEW.original_attempt_evidence_bytes IS NULL
                OR current_setting('world.canonical_claim_namespace', TRUE) IS DISTINCT FROM NEW.target_namespace
                OR current_setting('world.canonical_claim_tenant', TRUE) IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
                OR current_setting('world.canonical_claim_request', TRUE) IS DISTINCT FROM NEW.control_plane_request_id
                OR current_setting('world.canonical_claim_target', TRUE) IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
                OR current_setting('world.canonical_claim_fence', TRUE) IS DISTINCT FROM NEW.world_execution_fence::TEXT THEN
                RAISE EXCEPTION 'World execution intent may be claimed only once by its exact complete owner observation and serialization fence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.operation_state = 'PENDING' AND NEW.operation_state = 'EXECUTING' THEN
            IF NOT OLD.execution_claimed OR NOT NEW.execution_claimed
                OR NEW.active_transaction_id IS DISTINCT FROM txid_current()
                OR NEW.world_instance_id IS NOT NULL OR NEW.terminal_at IS NOT NULL
                OR NEW.game_session_owner_mutation_id IS NULL
                OR NEW.original_attempt_read_request_id IS NULL
                OR NEW.original_game_session_lease_expires_at IS NULL
                OR NEW.original_attempt_evidence_bytes IS NULL
                OR current_setting('world.canonical_execution_namespace', TRUE) IS DISTINCT FROM NEW.target_namespace
                OR current_setting('world.canonical_execution_tenant', TRUE) IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
                OR current_setting('world.canonical_execution_request', TRUE) IS DISTINCT FROM NEW.control_plane_request_id
                OR current_setting('world.canonical_execution_target', TRUE) IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
                OR current_setting('world.canonical_execution_fence', TRUE) IS DISTINCT FROM NEW.world_execution_fence::TEXT
                OR current_setting('world.canonical_execution_input_digest', TRUE) IS DISTINCT FROM NEW.preparation_input_digest THEN
                RAISE EXCEPTION 'World EXECUTING state requires its exact current transaction, complete owner observation and execution fence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.operation_state = 'PENDING' AND NEW.operation_state = 'ABORTED' THEN
            IF NEW.execution_claimed IS DISTINCT FROM OLD.execution_claimed
                OR NEW.active_transaction_id IS NOT NULL OR NEW.world_instance_id IS NOT NULL
                OR NEW.terminal_at IS NULL
                OR current_setting('world.canonical_abort_namespace', TRUE) IS DISTINCT FROM NEW.target_namespace
                OR current_setting('world.canonical_abort_tenant', TRUE) IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
                OR current_setting('world.canonical_abort_request', TRUE) IS DISTINCT FROM NEW.control_plane_request_id
                OR current_setting('world.canonical_abort_target', TRUE) IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
                OR (OLD.game_session_owner_mutation_id IS NOT NULL AND (
                    current_setting('world.canonical_abort_mutation', TRUE) IS DISTINCT FROM OLD.game_session_owner_mutation_id::TEXT
                    OR current_setting('world.canonical_abort_attempt_read', TRUE) IS DISTINCT FROM OLD.original_attempt_read_request_id::TEXT
                    OR current_setting('world.canonical_abort_lease_expiry', TRUE) IS DISTINCT FROM OLD.original_game_session_lease_expires_at::TEXT
                    OR current_setting('world.canonical_abort_observation_digest', TRUE) IS DISTINCT FROM OLD.original_attempt_evidence_sha256)) THEN
                RAISE EXCEPTION 'World ABORTED state requires its exact serialized terminal fence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.operation_state = 'EXECUTING' AND NEW.operation_state = 'COMMITTED' THEN
            IF OLD.active_transaction_id IS DISTINCT FROM txid_current()
                OR NEW.execution_claimed IS DISTINCT FROM TRUE
                OR NEW.active_transaction_id IS NOT NULL
                OR NEW.terminal_at IS NULL
                OR current_setting('world.canonical_execution_namespace', TRUE) IS DISTINCT FROM NEW.target_namespace
                OR current_setting('world.canonical_execution_tenant', TRUE) IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
                OR current_setting('world.canonical_execution_request', TRUE) IS DISTINCT FROM NEW.control_plane_request_id
                OR current_setting('world.canonical_execution_target', TRUE) IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
                OR current_setting('world.canonical_execution_fence', TRUE) IS DISTINCT FROM NEW.world_execution_fence::TEXT
                OR current_setting('world.canonical_execution_input_digest', TRUE) IS DISTINCT FROM NEW.preparation_input_digest
                OR NOT "${serviceSchema}".world_execution_has_exact_materialized_result(NEW, NEW.world_instance_id) THEN
                RAISE EXCEPTION 'World COMMITTED state requires the current exact V35 preparation and V34 association'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'World execution state transition is not permitted'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.operation_state = 'PENDING' THEN
        IF NEW.execution_claimed OR NEW.active_transaction_id IS NOT NULL
            OR NEW.world_instance_id IS NOT NULL OR NEW.terminal_at IS NOT NULL
            OR NEW.game_session_owner_mutation_id IS NULL
            OR NEW.original_attempt_read_request_id IS NULL
            OR NEW.original_game_session_lease_expires_at IS NULL
            OR NEW.original_attempt_evidence_bytes IS NULL THEN
            RAISE EXCEPTION 'A fresh World execution intent requires its exact Account owner observation'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.operation_state = 'ABORTED' THEN
        IF NEW.execution_claimed OR NEW.active_transaction_id IS NOT NULL
            OR NEW.world_instance_id IS NOT NULL OR NEW.terminal_at IS NULL
            OR current_setting('world.canonical_abort_namespace', TRUE) IS DISTINCT FROM NEW.target_namespace
            OR current_setting('world.canonical_abort_tenant', TRUE) IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
            OR current_setting('world.canonical_abort_request', TRUE) IS DISTINCT FROM NEW.control_plane_request_id
            OR current_setting('world.canonical_abort_target', TRUE) IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
            OR (NEW.game_session_owner_mutation_id IS NOT NULL AND (
                current_setting('world.canonical_abort_mutation', TRUE) IS DISTINCT FROM NEW.game_session_owner_mutation_id::TEXT
                OR current_setting('world.canonical_abort_attempt_read', TRUE) IS DISTINCT FROM NEW.original_attempt_read_request_id::TEXT
                OR current_setting('world.canonical_abort_lease_expiry', TRUE) IS DISTINCT FROM NEW.original_game_session_lease_expires_at::TEXT
                OR current_setting('world.canonical_abort_observation_digest', TRUE) IS DISTINCT FROM NEW.original_attempt_evidence_sha256)) THEN
            RAISE EXCEPTION 'A born-ABORTED World operation requires its exact serialized abort fence'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'World execution rows must be born PENDING or terminally ABORTED'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_enforce_canonical_instance_execution_transition() FROM PUBLIC;
-- [jooq ignore stop]
