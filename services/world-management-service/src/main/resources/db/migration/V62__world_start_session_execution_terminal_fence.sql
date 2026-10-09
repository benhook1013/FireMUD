-- A World preparation operation is retained before its execution transaction so that a later
-- serialized abort can name the exact original tuple and source binding without re-reading remote
-- sources. EXECUTING is visible only inside the one materialization transaction and may never
-- commit; COMMITTED includes both the V35 rows and V34 association, while ABORTED fences retries.
-- Historical V35/V34 rows are deliberately not backfilled into this operation ledger.
CREATE SEQUENCE world_canonical_instance_execution_fence_seq AS BIGINT MINVALUE 1;

CREATE TABLE world_canonical_instance_execution (
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    canonical_game_instance_id UUID NOT NULL,
    canonical_world_slug VARCHAR(120) NOT NULL,
    game_template_id BIGINT NOT NULL,
    original_post_authorization_tuple BYTEA NOT NULL
        CHECK (octet_length(original_post_authorization_tuple) BETWEEN 1 AND 262144),
    original_post_authorization_tuple_sha256 VARCHAR(64) NOT NULL
        CHECK (original_post_authorization_tuple_sha256 ~ '^[0-9a-f]{64}$'),
    account_world_participation_id UUID NOT NULL,
    account_world_participation_fence BIGINT NOT NULL CHECK (account_world_participation_fence > 0),
    game_session_owner_attempt_id UUID NOT NULL,
    game_session_owner_fence BIGINT NOT NULL CHECK (game_session_owner_fence > 0),
    preparation_input_digest VARCHAR(71) NOT NULL
        CHECK (preparation_input_digest ~ '^sha256:[0-9a-f]{64}$'),
    preparation_input_json TEXT NOT NULL CHECK (length(preparation_input_json) > 0),
    world_execution_fence BIGINT NOT NULL UNIQUE CHECK (world_execution_fence > 0),
    operation_state VARCHAR(16) NOT NULL
        CHECK (operation_state IN ('PENDING', 'EXECUTING', 'COMMITTED', 'ABORTED')),
    execution_claimed BOOLEAN NOT NULL DEFAULT FALSE,
    active_transaction_id BIGINT,
    world_instance_id BIGINT REFERENCES world_instance(id) ON DELETE RESTRICT
        DEFERRABLE INITIALLY DEFERRED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    terminal_at TIMESTAMPTZ,
    PRIMARY KEY (target_namespace, canonical_tenant_id, control_plane_request_id),
    UNIQUE (canonical_game_instance_id),
    CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (account_world_participation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (game_session_owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (game_template_id > 0),
    CHECK (
        (operation_state = 'PENDING' AND active_transaction_id IS NULL
            AND world_instance_id IS NULL AND terminal_at IS NULL)
        OR (operation_state = 'EXECUTING' AND execution_claimed IS TRUE
            AND active_transaction_id > 0
            AND world_instance_id IS NULL AND terminal_at IS NULL)
        OR (operation_state = 'COMMITTED' AND execution_claimed IS TRUE
            AND active_transaction_id IS NULL
            AND world_instance_id > 0 AND terminal_at IS NOT NULL)
        OR (operation_state = 'ABORTED' AND active_transaction_id IS NULL
            AND world_instance_id IS NULL AND terminal_at IS NOT NULL)
    )
);
-- [jooq ignore start]
REVOKE ALL ON world_canonical_instance_execution FROM PUBLIC;
REVOKE ALL ON SEQUENCE world_canonical_instance_execution_fence_seq FROM PUBLIC;

CREATE FUNCTION world_validate_canonical_instance_execution_input(
    p_input_json TEXT,
    p_input_digest TEXT,
    p_original_tuple BYTEA,
    p_account_participation_id UUID,
    p_account_participation_fence BIGINT,
    p_game_session_attempt_id UUID,
    p_game_session_fence BIGINT,
    p_canonical_game_instance_id UUID
) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    input JSONB;
    tuple_value JSONB;
    original JSONB;
    descriptor JSONB;
    action_scope JSONB;
    target JSONB;
    target_owner JSONB;
BEGIN
    IF p_input_json IS NULL OR length(p_input_json) = 0
        OR p_input_digest IS NULL OR p_input_digest !~ '^sha256:[0-9a-f]{64}$'
        OR p_input_digest IS DISTINCT FROM 'sha256:' || encode(sha256(convert_to(p_input_json, 'UTF8')), 'hex')
        OR p_original_tuple IS NULL OR octet_length(p_original_tuple) NOT BETWEEN 1 AND 262144
        OR p_account_participation_id IS NULL
        OR p_account_participation_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR p_account_participation_fence IS NULL OR p_account_participation_fence <= 0
        OR p_game_session_attempt_id IS NULL
        OR p_game_session_attempt_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR p_game_session_fence IS NULL OR p_game_session_fence <= 0
        OR p_canonical_game_instance_id IS NULL
        OR p_canonical_game_instance_id = '00000000-0000-0000-0000-000000000000'::UUID THEN
        RAISE EXCEPTION 'World execution identity or preparation input is malformed'
            USING ERRCODE = '23514';
    END IF;

    input := p_input_json::JSONB;
    tuple_value := convert_from(p_original_tuple, 'UTF8')::JSONB;
    original := tuple_value->'preAuthorizationReservationTuple';
    action_scope := tuple_value->'scope';
    target := tuple_value->'target';
    target_owner := tuple_value->'targetOwner';
    descriptor := (input->'gameSessionReadEvidence'->>'descriptorJson')::JSONB;
    IF tuple_value->>'tupleSchemaId' IS DISTINCT FROM 'postAuthorizationExecutionTuple'
        OR tuple_value->>'tupleSchemaVersion' IS DISTINCT FROM '1'
        OR tuple_value->>'issuanceKind' IS DISTINCT FROM 'human_operator'
        OR tuple_value->>'controlPlaneRequestId' IS DISTINCT FROM original->'actionFamilyRequestIdentity'->>'requestId'
        OR action_scope->>'targetNamespace' IS DISTINCT FROM input->'identity'->>'targetNamespace'
        OR action_scope->>'tenantId' IS DISTINCT FROM input->'identity'->>'canonicalTenantId'
        OR tuple_value->>'controlPlaneRequestId' IS DISTINCT FROM input->'identity'->>'controlPlaneRequestId'
        OR target_owner->>'ownerService' IS DISTINCT FROM 'game-session-service'
        OR original->>'actionFamily' IS DISTINCT FROM 'StartSession'
        OR (target->>'gameTemplateId')::BIGINT IS DISTINCT FROM (descriptor->>'gameTemplateId')::BIGINT
        OR input->'identity'->>'canonicalGameInstanceId' IS DISTINCT FROM p_canonical_game_instance_id::TEXT
        OR input->'gameSessionReadEvidence'->>'canonicalGameInstanceId' IS DISTINCT FROM p_canonical_game_instance_id::TEXT
        OR input->'gameSessionReadRequest'->>'canonicalGameInstanceId' IS DISTINCT FROM p_canonical_game_instance_id::TEXT
        OR input->'identity'->>'targetNamespace' IS DISTINCT FROM input->'gameSessionReadEvidence'->>'targetNamespace'
        OR input->'identity'->>'canonicalTenantId' IS DISTINCT FROM input->'gameSessionReadEvidence'->>'canonicalTenantId'
        OR input->'identity'->>'worldSlug' IS DISTINCT FROM input->'gameSessionReadEvidence'->>'worldSlug'
        OR input->'identity'->>'controlPlaneRequestId' IS DISTINCT FROM input->'gameSessionReadEvidence'->>'controlPlaneRequestId'
        OR tuple_value->'authorityEvidenceBundle'->'accountProjectionEvidence'->>'expiresAt' IS NULL THEN
        RAISE EXCEPTION 'World execution identity differs from the exact StartSession tuple or World target'
            USING ERRCODE = '23514';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION world_validate_canonical_instance_execution_input(TEXT, TEXT, BYTEA, UUID, BIGINT, UUID, BIGINT, UUID) FROM PUBLIC;

CREATE FUNCTION world_lock_canonical_instance_execution(
    p_target_namespace TEXT, p_canonical_tenant_id UUID,
    p_control_plane_request_id TEXT, p_canonical_game_instance_id UUID
) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    -- The request lock comes first so a substituted canonical target cannot race the original
    -- request. The target lock then serializes different requests aimed at the same World target.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'world-canonical-execution-request:' || p_target_namespace || ':'
            || p_canonical_tenant_id::TEXT || ':' || p_control_plane_request_id, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'world-canonical-execution-target:' || p_canonical_game_instance_id::TEXT, 0));
END;
$$;
REVOKE ALL ON FUNCTION world_lock_canonical_instance_execution(TEXT, UUID, TEXT, UUID) FROM PUBLIC;

CREATE FUNCTION world_persist_canonical_instance_execution_intent(
    p_input_json TEXT, p_input_digest TEXT, p_original_tuple BYTEA,
    p_account_participation_id UUID, p_account_participation_fence BIGINT,
    p_game_session_attempt_id UUID, p_game_session_fence BIGINT,
    p_canonical_game_instance_id UUID
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
    -- Use the database wall clock after both shared identity locks; transaction-start timestamps
    -- would admit a transaction that waited past the original Account bound.
    IF projection_expiry <= clock_timestamp() THEN
        RAISE EXCEPTION 'Original StartSession authorization expired before World intent retention'
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
            OR prior.preparation_input_digest IS DISTINCT FROM p_input_digest
            OR prior.preparation_input_json IS DISTINCT FROM p_input_json THEN
            RAISE EXCEPTION 'World execution request is already bound to a different full identity'
                USING ERRCODE = '23505';
        END IF;
        -- A previously retained intent is never automatically restarted. The caller performs an
        -- exact terminal read; PENDING requires the shared-lock durable-abort path.
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
        preparation_input_digest, preparation_input_json, world_execution_fence,
        operation_state)
    VALUES (
        v_target_namespace, v_canonical_tenant_id, v_request_id,
        p_canonical_game_instance_id, v_world_slug, v_game_template_id,
        p_original_tuple, encode(sha256(p_original_tuple), 'hex'),
        p_account_participation_id, p_account_participation_fence,
        p_game_session_attempt_id, p_game_session_fence,
        p_input_digest, p_input_json,
        nextval('"${serviceSchema}".world_canonical_instance_execution_fence_seq'),
        'PENDING')
    RETURNING world_execution_fence INTO prior.world_execution_fence;
    RETURN prior.world_execution_fence;
END;
$$;
REVOKE ALL ON FUNCTION world_persist_canonical_instance_execution_intent(TEXT, TEXT, BYTEA, UUID, BIGINT, UUID, BIGINT, UUID) FROM PUBLIC;

CREATE FUNCTION world_claim_canonical_instance_execution(
    p_input_json TEXT, p_input_digest TEXT, p_original_tuple BYTEA,
    p_account_participation_id UUID, p_account_participation_fence BIGINT,
    p_game_session_attempt_id UUID, p_game_session_fence BIGINT,
    p_canonical_game_instance_id UUID
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    input JSONB;
    tuple_value JSONB;
    operation "${serviceSchema}".world_canonical_instance_execution%ROWTYPE;
    v_target_namespace TEXT;
    v_canonical_tenant_id UUID;
    v_request_id TEXT;
    original_expiry TIMESTAMPTZ;
BEGIN
    PERFORM "${serviceSchema}".world_validate_canonical_instance_execution_input(
        p_input_json, p_input_digest, p_original_tuple,
        p_account_participation_id, p_account_participation_fence,
        p_game_session_attempt_id, p_game_session_fence, p_canonical_game_instance_id);
    input := p_input_json::JSONB;
    tuple_value := convert_from(p_original_tuple, 'UTF8')::JSONB;
    v_target_namespace := input->'identity'->>'targetNamespace';
    v_canonical_tenant_id := (input->'identity'->>'canonicalTenantId')::UUID;
    v_request_id := input->'identity'->>'controlPlaneRequestId';
    PERFORM "${serviceSchema}".world_lock_canonical_instance_execution(
        v_target_namespace, v_canonical_tenant_id, v_request_id, p_canonical_game_instance_id);
    original_expiry := (tuple_value->'authorityEvidenceBundle'->'accountProjectionEvidence'->>'expiresAt')::TIMESTAMPTZ;
    IF original_expiry <= clock_timestamp() THEN
        RAISE EXCEPTION 'Original StartSession authorization expired before World execution claim'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e
    WHERE e.target_namespace = v_target_namespace
        AND e.canonical_tenant_id = v_canonical_tenant_id
        AND e.control_plane_request_id = v_request_id
    FOR UPDATE;
    IF NOT FOUND
        OR operation.canonical_game_instance_id IS DISTINCT FROM p_canonical_game_instance_id
        OR operation.original_post_authorization_tuple IS DISTINCT FROM p_original_tuple
        OR operation.account_world_participation_id IS DISTINCT FROM p_account_participation_id
        OR operation.account_world_participation_fence IS DISTINCT FROM p_account_participation_fence
        OR operation.game_session_owner_attempt_id IS DISTINCT FROM p_game_session_attempt_id
        OR operation.game_session_owner_fence IS DISTINCT FROM p_game_session_fence
        OR operation.preparation_input_digest IS DISTINCT FROM p_input_digest
        OR operation.preparation_input_json IS DISTINCT FROM p_input_json THEN
        RAISE EXCEPTION 'World execution claim differs from the exact original full identity'
            USING ERRCODE = '23514';
    END IF;
    IF operation.operation_state = 'COMMITTED' THEN
        RETURN 0;
    END IF;
    IF operation.operation_state <> 'PENDING' OR operation.execution_claimed THEN
        RETURN 0;
    END IF;
    PERFORM set_config('world.canonical_claim_namespace', operation.target_namespace, TRUE);
    PERFORM set_config('world.canonical_claim_tenant', operation.canonical_tenant_id::TEXT, TRUE);
    PERFORM set_config('world.canonical_claim_request', operation.control_plane_request_id, TRUE);
    PERFORM set_config('world.canonical_claim_target', operation.canonical_game_instance_id::TEXT, TRUE);
    PERFORM set_config('world.canonical_claim_fence', operation.world_execution_fence::TEXT, TRUE);
    UPDATE "${serviceSchema}".world_canonical_instance_execution e
    SET execution_claimed = TRUE
    WHERE e.target_namespace = operation.target_namespace
        AND e.canonical_tenant_id = operation.canonical_tenant_id
        AND e.control_plane_request_id = operation.control_plane_request_id;
    RETURN operation.world_execution_fence;
END;
$$;
REVOKE ALL ON FUNCTION world_claim_canonical_instance_execution(TEXT, TEXT, BYTEA, UUID, BIGINT, UUID, BIGINT, UUID) FROM PUBLIC;

CREATE FUNCTION world_begin_canonical_instance_execution(
    p_input_json TEXT, p_input_digest TEXT, p_original_tuple BYTEA,
    p_account_participation_id UUID, p_account_participation_fence BIGINT,
    p_game_session_attempt_id UUID, p_game_session_fence BIGINT,
    p_canonical_game_instance_id UUID, p_world_execution_fence BIGINT
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    input JSONB;
    v_target_namespace TEXT;
    v_canonical_tenant_id UUID;
    v_request_id TEXT;
    operation "${serviceSchema}".world_canonical_instance_execution%ROWTYPE;
BEGIN
    PERFORM "${serviceSchema}".world_validate_canonical_instance_execution_input(
        p_input_json, p_input_digest, p_original_tuple,
        p_account_participation_id, p_account_participation_fence,
        p_game_session_attempt_id, p_game_session_fence, p_canonical_game_instance_id);
    input := p_input_json::JSONB;
    v_target_namespace := input->'identity'->>'targetNamespace';
    v_canonical_tenant_id := (input->'identity'->>'canonicalTenantId')::UUID;
    v_request_id := input->'identity'->>'controlPlaneRequestId';
    PERFORM "${serviceSchema}".world_lock_canonical_instance_execution(
        v_target_namespace, v_canonical_tenant_id, v_request_id, p_canonical_game_instance_id);

    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e
    WHERE e.target_namespace = v_target_namespace
        AND e.canonical_tenant_id = v_canonical_tenant_id
        AND e.control_plane_request_id = v_request_id
    FOR UPDATE;
    IF NOT FOUND
        OR operation.canonical_game_instance_id IS DISTINCT FROM p_canonical_game_instance_id
        OR operation.original_post_authorization_tuple IS DISTINCT FROM p_original_tuple
        OR operation.account_world_participation_id IS DISTINCT FROM p_account_participation_id
        OR operation.account_world_participation_fence IS DISTINCT FROM p_account_participation_fence
        OR operation.game_session_owner_attempt_id IS DISTINCT FROM p_game_session_attempt_id
        OR operation.game_session_owner_fence IS DISTINCT FROM p_game_session_fence
        OR operation.preparation_input_digest IS DISTINCT FROM p_input_digest
        OR operation.preparation_input_json IS DISTINCT FROM p_input_json
        OR operation.world_execution_fence IS DISTINCT FROM p_world_execution_fence THEN
        RAISE EXCEPTION 'World execution intent does not match the exact original full identity'
            USING ERRCODE = '23514';
    END IF;
    IF operation.operation_state = 'COMMITTED' THEN
        -- The caller must perform a separate read-only exact terminal readback. No V35 call or
        -- operation update occurs on this branch.
        RETURN 0;
    END IF;
    IF operation.operation_state <> 'PENDING' OR NOT operation.execution_claimed THEN
        RAISE EXCEPTION 'World execution intent is terminal or already in flight'
            USING ERRCODE = '23514';
    END IF;

    PERFORM set_config('world.canonical_execution_namespace', operation.target_namespace, TRUE);
    PERFORM set_config('world.canonical_execution_tenant', operation.canonical_tenant_id::TEXT, TRUE);
    PERFORM set_config('world.canonical_execution_request', operation.control_plane_request_id, TRUE);
    PERFORM set_config('world.canonical_execution_target', operation.canonical_game_instance_id::TEXT, TRUE);
    PERFORM set_config('world.canonical_execution_fence', operation.world_execution_fence::TEXT, TRUE);
    PERFORM set_config('world.canonical_execution_input_digest', operation.preparation_input_digest, TRUE);
    UPDATE "${serviceSchema}".world_canonical_instance_execution e
    SET operation_state = 'EXECUTING', active_transaction_id = txid_current()
    WHERE e.target_namespace = operation.target_namespace
        AND e.canonical_tenant_id = operation.canonical_tenant_id
        AND e.control_plane_request_id = operation.control_plane_request_id;
    RETURN operation.world_execution_fence;
END;
$$;
REVOKE ALL ON FUNCTION world_begin_canonical_instance_execution(TEXT, TEXT, BYTEA, UUID, BIGINT, UUID, BIGINT, UUID, BIGINT) FROM PUBLIC;

CREATE FUNCTION world_require_active_canonical_instance_execution(
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
    IF NOT FOUND THEN
        RAISE EXCEPTION 'V35 requires the exact current World execution identity and fence'
            USING ERRCODE = '23514';
    END IF;
    original_tuple := convert_from(operation.original_post_authorization_tuple, 'UTF8')::JSONB;
    original_expiry := (original_tuple->'authorityEvidenceBundle'->'accountProjectionEvidence'->>'expiresAt')::TIMESTAMPTZ;
    -- This hook is inserted after V35's publication-owner, canonical-target, and request locks.
    IF original_expiry <= clock_timestamp() THEN
        RAISE EXCEPTION 'Original StartSession authorization expired before World execution admission'
            USING ERRCODE = '23514';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION world_require_active_canonical_instance_execution(TEXT, TEXT) FROM PUBLIC;

-- Preserve the reviewed V35/V40/V42/V61 source guards as an owner-private inner implementation.
ALTER FUNCTION world_prepare_canonical_instance(TEXT, TEXT)
    RENAME TO world_prepare_canonical_instance_v61_impl;

CREATE FUNCTION world_prepare_canonical_instance(p_input_json TEXT, p_input_digest TEXT)
RETURNS TABLE(world_instance_id BIGINT, private_game_instance_key BIGINT)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    RETURN QUERY SELECT *
        FROM "${serviceSchema}".world_prepare_canonical_instance_v61_impl(p_input_json, p_input_digest);
END;
$$;
REVOKE ALL ON FUNCTION world_prepare_canonical_instance(TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION world_prepare_canonical_instance_v61_impl(TEXT, TEXT) FROM PUBLIC;

-- The ordinary application call path enters the operation lock before the V35 owner/publication
-- locks; the expiry hook above rechecks only after V35 has acquired all of its own locks.
DO $migration$
DECLARE
    preparation_function REGPROCEDURE :=
        '"${serviceSchema}".world_prepare_canonical_instance_v61_impl(text,text)'::REGPROCEDURE;
    original_definition TEXT;
    lock_anchor TEXT := $anchor$    SELECT * INTO prior FROM "${serviceSchema}".world_canonical_instance_preparation$anchor$;
    lock_replacement TEXT := $replacement$    PERFORM "${serviceSchema}".world_require_active_canonical_instance_execution(
        p_input_json, p_input_digest);

    SELECT * INTO prior FROM "${serviceSchema}".world_canonical_instance_preparation$replacement$;
BEGIN
    SELECT pg_get_functiondef(preparation_function) INTO STRICT original_definition;
    IF (length(original_definition) - length(replace(original_definition, lock_anchor, '')))
            / length(lock_anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected one post-V61 canonical preparation prior-row lookup';
    END IF;
    EXECUTE replace(original_definition, lock_anchor, lock_replacement);
END;
$migration$;

CREATE FUNCTION world_execution_has_exact_materialized_result(
    p_operation "${serviceSchema}".world_canonical_instance_execution,
    p_world_instance_id BIGINT
) RETURNS BOOLEAN
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
    SELECT p_world_instance_id IS NOT NULL AND p_world_instance_id > 0 AND EXISTS (
        SELECT 1
        FROM world_canonical_instance_preparation p
        JOIN world_canonical_instance_association a
            ON a.canonical_game_instance_id = p.canonical_game_instance_id
            AND a.world_instance_id = p.world_instance_id
        WHERE p.canonical_game_instance_id = (p_operation).canonical_game_instance_id
            AND p.target_namespace = (p_operation).target_namespace
            AND p.canonical_tenant_id = (p_operation).canonical_tenant_id
            AND p.control_plane_request_id = (p_operation).control_plane_request_id
            AND p.input_digest = (p_operation).preparation_input_digest
            AND p.input_json = (p_operation).preparation_input_json
            AND p.world_instance_id = p_world_instance_id
            AND a.canonical_target_namespace = (p_operation).target_namespace
            AND a.canonical_tenant_id = (p_operation).canonical_tenant_id
            AND a.control_plane_request_id = (p_operation).control_plane_request_id
            AND a.game_template_id = (p_operation).game_template_id)
$$;
REVOKE ALL ON FUNCTION world_execution_has_exact_materialized_result("${serviceSchema}".world_canonical_instance_execution, BIGINT) FROM PUBLIC;

CREATE FUNCTION world_commit_canonical_instance_execution(p_world_instance_id BIGINT)
RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    operation "${serviceSchema}".world_canonical_instance_execution%ROWTYPE;
BEGIN
    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e
    WHERE e.target_namespace = current_setting('world.canonical_execution_namespace', TRUE)
        AND e.canonical_tenant_id::TEXT = current_setting('world.canonical_execution_tenant', TRUE)
        AND e.control_plane_request_id = current_setting('world.canonical_execution_request', TRUE)
        AND e.canonical_game_instance_id::TEXT = current_setting('world.canonical_execution_target', TRUE)
        AND e.world_execution_fence::TEXT = current_setting('world.canonical_execution_fence', TRUE)
        AND e.preparation_input_digest = current_setting('world.canonical_execution_input_digest', TRUE)
        AND e.operation_state = 'EXECUTING'
        AND e.active_transaction_id = txid_current()
    FOR UPDATE;
    IF NOT FOUND OR p_world_instance_id IS NULL OR p_world_instance_id <= 0
        OR NOT "${serviceSchema}".world_execution_has_exact_materialized_result(
            operation, p_world_instance_id) THEN
        RAISE EXCEPTION 'World execution cannot commit without its exact V35 preparation and V34 association'
            USING ERRCODE = '23514';
    END IF;
    UPDATE "${serviceSchema}".world_canonical_instance_execution e
    SET operation_state = 'COMMITTED', active_transaction_id = NULL,
        world_instance_id = p_world_instance_id, terminal_at = clock_timestamp()
    WHERE e.target_namespace = operation.target_namespace
        AND e.canonical_tenant_id = operation.canonical_tenant_id
        AND e.control_plane_request_id = operation.control_plane_request_id;
END;
$$;
REVOKE ALL ON FUNCTION world_commit_canonical_instance_execution(BIGINT) FROM PUBLIC;

CREATE FUNCTION world_abort_canonical_instance_execution(
    p_target_namespace TEXT, p_canonical_tenant_id UUID, p_control_plane_request_id TEXT,
    p_canonical_game_instance_id UUID, p_preparation_input_json TEXT, p_preparation_input_digest TEXT,
    p_original_tuple BYTEA, p_account_participation_id UUID, p_account_participation_fence BIGINT,
    p_game_session_attempt_id UUID, p_game_session_fence BIGINT
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
    IF p_original_tuple IS NULL OR p_preparation_input_digest !~ '^sha256:[0-9a-f]{64}$' THEN
        RAISE EXCEPTION 'Exact original identity is required for World abort'
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
        OR operation.game_session_owner_fence IS DISTINCT FROM p_game_session_fence THEN
        RAISE EXCEPTION 'World abort differs from the exact original full execution identity'
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
REVOKE ALL ON FUNCTION world_abort_canonical_instance_execution(TEXT, UUID, TEXT, UUID, TEXT, TEXT, BYTEA, UUID, BIGINT, UUID, BIGINT) FROM PUBLIC;

CREATE FUNCTION world_enforce_canonical_instance_execution_transition()
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
            OR NEW.preparation_input_digest IS DISTINCT FROM OLD.preparation_input_digest
            OR NEW.preparation_input_json IS DISTINCT FROM OLD.preparation_input_json
            OR NEW.world_execution_fence IS DISTINCT FROM OLD.world_execution_fence
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'World execution identity is immutable and terminal states cannot be reopened'
                USING ERRCODE = '23514';
        END IF;
        IF OLD.operation_state = 'PENDING' AND NEW.operation_state = 'PENDING' THEN
            IF OLD.execution_claimed OR NOT NEW.execution_claimed
                OR OLD.active_transaction_id IS DISTINCT FROM NEW.active_transaction_id
                OR OLD.world_instance_id IS DISTINCT FROM NEW.world_instance_id
                OR OLD.terminal_at IS DISTINCT FROM NEW.terminal_at
                OR current_setting('world.canonical_claim_namespace', TRUE) IS DISTINCT FROM NEW.target_namespace
                OR current_setting('world.canonical_claim_tenant', TRUE) IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
                OR current_setting('world.canonical_claim_request', TRUE) IS DISTINCT FROM NEW.control_plane_request_id
                OR current_setting('world.canonical_claim_target', TRUE) IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
                OR current_setting('world.canonical_claim_fence', TRUE) IS DISTINCT FROM NEW.world_execution_fence::TEXT THEN
                RAISE EXCEPTION 'World execution intent may be claimed only once by its exact serialization fence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.operation_state = 'PENDING' AND NEW.operation_state = 'EXECUTING' THEN
            IF NOT OLD.execution_claimed OR NOT NEW.execution_claimed
                OR NEW.active_transaction_id IS DISTINCT FROM txid_current()
                OR NEW.world_instance_id IS NOT NULL OR NEW.terminal_at IS NOT NULL
                OR current_setting('world.canonical_execution_namespace', TRUE) IS DISTINCT FROM NEW.target_namespace
                OR current_setting('world.canonical_execution_tenant', TRUE) IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
                OR current_setting('world.canonical_execution_request', TRUE) IS DISTINCT FROM NEW.control_plane_request_id
                OR current_setting('world.canonical_execution_target', TRUE) IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT
                OR current_setting('world.canonical_execution_fence', TRUE) IS DISTINCT FROM NEW.world_execution_fence::TEXT
                OR current_setting('world.canonical_execution_input_digest', TRUE) IS DISTINCT FROM NEW.preparation_input_digest THEN
                RAISE EXCEPTION 'World EXECUTING state requires its exact current transaction and execution fence'
                    USING ERRCODE = '23514';
            END IF;
        ELSIF OLD.operation_state = 'PENDING' AND NEW.operation_state = 'ABORTED' THEN
            IF NEW.execution_claimed IS DISTINCT FROM OLD.execution_claimed
                OR NEW.active_transaction_id IS NOT NULL OR NEW.world_instance_id IS NOT NULL
                OR NEW.terminal_at IS NULL
                OR current_setting('world.canonical_abort_namespace', TRUE) IS DISTINCT FROM NEW.target_namespace
                OR current_setting('world.canonical_abort_tenant', TRUE) IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
                OR current_setting('world.canonical_abort_request', TRUE) IS DISTINCT FROM NEW.control_plane_request_id
                OR current_setting('world.canonical_abort_target', TRUE) IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT THEN
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
            OR NEW.world_instance_id IS NOT NULL OR NEW.terminal_at IS NOT NULL THEN
            RAISE EXCEPTION 'World execution intent must be born unclaimed and PENDING'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.operation_state = 'ABORTED' THEN
        IF NEW.execution_claimed OR NEW.active_transaction_id IS NOT NULL
            OR NEW.world_instance_id IS NOT NULL OR NEW.terminal_at IS NULL
            OR current_setting('world.canonical_abort_namespace', TRUE) IS DISTINCT FROM NEW.target_namespace
            OR current_setting('world.canonical_abort_tenant', TRUE) IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
            OR current_setting('world.canonical_abort_request', TRUE) IS DISTINCT FROM NEW.control_plane_request_id
            OR current_setting('world.canonical_abort_target', TRUE) IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT THEN
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
CREATE TRIGGER trg_world_canonical_instance_execution_transition
    BEFORE INSERT OR UPDATE ON world_canonical_instance_execution
    FOR EACH ROW EXECUTE FUNCTION world_enforce_canonical_instance_execution_transition();
REVOKE ALL ON FUNCTION world_enforce_canonical_instance_execution_transition() FROM PUBLIC;

CREATE FUNCTION world_require_canonical_instance_execution_terminal()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    final_state TEXT;
BEGIN
    SELECT operation_state INTO final_state
    FROM world_canonical_instance_execution
    WHERE target_namespace = NEW.target_namespace
        AND canonical_tenant_id = NEW.canonical_tenant_id
        AND control_plane_request_id = NEW.control_plane_request_id;
    IF final_state IS NULL OR final_state = 'EXECUTING' THEN
        RAISE EXCEPTION 'World EXECUTING state cannot commit without exact terminal settlement'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_world_canonical_instance_execution_no_inflight_commit
    AFTER INSERT OR UPDATE ON world_canonical_instance_execution
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION world_require_canonical_instance_execution_terminal();
REVOKE ALL ON FUNCTION world_require_canonical_instance_execution_terminal() FROM PUBLIC;

CREATE FUNCTION world_guard_canonical_instance_association_execution()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    operation "${serviceSchema}".world_canonical_instance_execution%ROWTYPE;
BEGIN
    SELECT * INTO operation FROM "${serviceSchema}".world_canonical_instance_execution e
    WHERE e.target_namespace = current_setting('world.canonical_execution_namespace', TRUE)
        AND e.canonical_tenant_id::TEXT = current_setting('world.canonical_execution_tenant', TRUE)
        AND e.control_plane_request_id = current_setting('world.canonical_execution_request', TRUE)
        AND e.canonical_game_instance_id::TEXT = current_setting('world.canonical_execution_target', TRUE)
        AND e.world_execution_fence::TEXT = current_setting('world.canonical_execution_fence', TRUE)
        AND e.operation_state = 'EXECUTING'
        AND e.active_transaction_id = txid_current()
    FOR KEY SHARE;
    IF NOT FOUND OR NEW.canonical_game_instance_id IS DISTINCT FROM operation.canonical_game_instance_id
        OR NEW.canonical_target_namespace IS DISTINCT FROM operation.target_namespace
        OR NEW.canonical_tenant_id IS DISTINCT FROM operation.canonical_tenant_id
        OR NEW.control_plane_request_id IS DISTINCT FROM operation.control_plane_request_id
        OR NEW.game_template_id IS DISTINCT FROM operation.game_template_id
        OR NEW.world_instance_id IS NULL
        OR NOT EXISTS (
            SELECT 1 FROM "${serviceSchema}".world_canonical_instance_preparation p
            WHERE p.canonical_game_instance_id = operation.canonical_game_instance_id
                AND p.target_namespace = operation.target_namespace
                AND p.canonical_tenant_id = operation.canonical_tenant_id
                AND p.control_plane_request_id = operation.control_plane_request_id
                AND p.input_digest = operation.preparation_input_digest
                AND p.input_json = operation.preparation_input_json
                AND p.world_instance_id = NEW.world_instance_id) THEN
        RAISE EXCEPTION 'V34 association requires the exact active World execution identity and fence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_canonical_instance_association_00_execution
    BEFORE INSERT ON world_canonical_instance_association
    FOR EACH ROW EXECUTE FUNCTION world_guard_canonical_instance_association_execution();
REVOKE ALL ON FUNCTION world_guard_canonical_instance_association_execution() FROM PUBLIC;
-- [jooq ignore stop]
