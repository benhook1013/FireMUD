-- World activation intent is bound to the distinct Account StartSession protection receipt before
-- the PREPARING-to-ACTIVE lifecycle CAS. Historical V43 operation rows remain untouched.
CREATE SEQUENCE world_canonical_instance_activation_fence_seq AS BIGINT MINVALUE 1;

CREATE TABLE world_canonical_instance_activation_fence (
    activation_request_id UUID PRIMARY KEY,
    protection_operation_digest VARCHAR(71) NOT NULL
        CHECK (protection_operation_digest ~ '^sha256:[0-9a-f]{64}$'),
    account_protection_id UUID NOT NULL
        CHECK (account_protection_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    account_protection_fence BIGINT NOT NULL CHECK (account_protection_fence > 0),
    account_protection_evidence_bytes BYTEA NOT NULL
        CHECK (octet_length(account_protection_evidence_bytes) BETWEEN 1 AND 209715200),
    account_protection_evidence_digest VARCHAR(71) NOT NULL
        CHECK (account_protection_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND account_protection_evidence_digest =
                'sha256:' || encode(sha256(account_protection_evidence_bytes), 'hex')),
    source_capture_reference_digest VARCHAR(64) NOT NULL
        CHECK (source_capture_reference_digest ~ '^[0-9a-f]{64}$'),
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    control_plane_request_id VARCHAR(128) NOT NULL,
    canonical_world_slug VARCHAR(120) NOT NULL,
    canonical_game_instance_id UUID NOT NULL UNIQUE
        CHECK (canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    original_post_authorization_tuple BYTEA NOT NULL
        CHECK (octet_length(original_post_authorization_tuple) BETWEEN 1 AND 262144),
    account_world_participation_id UUID NOT NULL
        CHECK (account_world_participation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    account_world_participation_fence BIGINT NOT NULL CHECK (account_world_participation_fence > 0),
    game_session_owner_mutation_id UUID NOT NULL
        CHECK (game_session_owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_attempt_id UUID NOT NULL
        CHECK (game_session_owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_fence BIGINT NOT NULL CHECK (game_session_owner_fence > 0),
    original_game_session_lease_expires_at TIMESTAMPTZ NOT NULL,
    canonical_realm_id UUID NOT NULL
        CHECK (canonical_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    catalog_revision BIGINT NOT NULL CHECK (catalog_revision > 0),
    expected_lifecycle_epoch BIGINT NOT NULL CHECK (expected_lifecycle_epoch > 0),
    expected_row_version BIGINT NOT NULL CHECK (expected_row_version >= 0),
    preparing_evidence_bytes BYTEA NOT NULL
        CHECK (octet_length(preparing_evidence_bytes) BETWEEN 1 AND 67108864),
    world_execution_fence BIGINT NOT NULL UNIQUE CHECK (world_execution_fence > 0),
    world_instance_id BIGINT NOT NULL REFERENCES world_instance(id) ON DELETE RESTRICT,
    world_activation_fence BIGINT NOT NULL UNIQUE CHECK (world_activation_fence > 0),
    operation_state VARCHAR(16) NOT NULL CHECK (operation_state IN ('PENDING', 'COMMITTED', 'ABORTED')),
    terminal_result_bytes BYTEA,
    terminal_result_digest VARCHAR(71),
    terminal_evidence_bytes BYTEA,
    terminal_evidence_digest VARCHAR(71),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    terminal_at TIMESTAMPTZ,
    CONSTRAINT ck_world_activation_fence_request_id_non_nil
        CHECK (activation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT ck_world_activation_fence_terminal_shape CHECK (
        (operation_state = 'PENDING'
            AND terminal_result_bytes IS NULL AND terminal_result_digest IS NULL
            AND terminal_evidence_bytes IS NULL AND terminal_evidence_digest IS NULL
            AND terminal_at IS NULL)
        OR (operation_state IN ('COMMITTED', 'ABORTED')
            AND terminal_result_bytes IS NOT NULL
            AND octet_length(terminal_result_bytes) BETWEEN 1 AND 402653184
            AND terminal_result_digest IS NOT NULL
            AND terminal_result_digest ~ '^sha256:[0-9a-f]{64}$'
            AND terminal_result_digest = 'sha256:' || encode(sha256(terminal_result_bytes), 'hex')
            AND terminal_evidence_bytes IS NOT NULL
            AND octet_length(terminal_evidence_bytes) BETWEEN 1 AND 805306368
            AND terminal_evidence_digest IS NOT NULL
            AND terminal_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND terminal_evidence_digest = 'sha256:' || encode(sha256(terminal_evidence_bytes), 'hex')
            AND terminal_at IS NOT NULL)
    )
);

CREATE INDEX idx_world_activation_fence_terminal
    ON world_canonical_instance_activation_fence(operation_state, world_instance_id);

-- [jooq ignore start]
REVOKE ALL ON world_canonical_instance_activation_fence FROM PUBLIC;
REVOKE ALL ON SEQUENCE world_canonical_instance_activation_fence_seq FROM PUBLIC;

-- V43 deliberately rejected the V64 schema-2 association because it lacked the launch-captured
-- realm/catalog pair. Widen only its old schema discriminator and preserve schema-1 history exactly.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$            AND a.schema_version=1$anchor$;
BEGIN
    SELECT pg_get_functiondef(
        '"${serviceSchema}".world_validate_canonical_activation_operation()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original)-length(replace(original,anchor,'')))/length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one V43 schema-1 activation guard';
    END IF;
    original := replace(original, anchor, $replacement$            AND a.schema_version IN (1,2)
            AND (a.schema_version=1 OR (
                a.canonical_realm_id IS NOT NULL
                AND a.catalog_revision IS NOT NULL
                AND w.canonical_realm_id=a.canonical_realm_id
                AND w.catalog_revision=a.catalog_revision
                AND p.input_json::JSONB->'identity'->>'canonicalRealmId'=a.canonical_realm_id::TEXT
                AND p.input_json::JSONB->'identity'->>'catalogRevision'=a.catalog_revision::TEXT))$replacement$);
    EXECUTE original;
END;
$migration$;

CREATE FUNCTION world_validate_original_start_session_activation_fence()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    account_evidence JSONB;
    account_request JSONB;
    preparing JSONB;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'World original StartSession activation fences are immutable and retained'
            USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.operation_state IS DISTINCT FROM 'PENDING'
            OR NEW.terminal_result_bytes IS NOT NULL OR NEW.terminal_result_digest IS NOT NULL
            OR NEW.terminal_evidence_bytes IS NOT NULL OR NEW.terminal_evidence_digest IS NOT NULL
            OR NEW.terminal_at IS NOT NULL THEN
            RAISE EXCEPTION 'World activation fence must be retained as PENDING before its exact terminal'
                USING ERRCODE = '23514';
        END IF;
        account_evidence := convert_from(NEW.account_protection_evidence_bytes, 'UTF8')::JSONB;
        preparing := convert_from(NEW.preparing_evidence_bytes, 'UTF8')::JSONB;
        IF jsonb_typeof(account_evidence) IS DISTINCT FROM 'object'
            OR (SELECT count(*) FROM jsonb_object_keys(account_evidence)) <> 8
            OR account_evidence->>'schema' IS DISTINCT FROM 'account-start-session-activation-protection-evidence/v1'
            OR account_evidence->>'accountProtectionId' IS DISTINCT FROM NEW.account_protection_id::TEXT
            OR account_evidence->>'accountProtectionFence' IS DISTINCT FROM NEW.account_protection_fence::TEXT
            OR account_evidence->>'originalSourceCaptureReferenceSha256'
                IS DISTINCT FROM NEW.source_capture_reference_digest
            OR NEW.source_capture_reference_digest IS DISTINCT FROM
                encode(sha256(decode(account_evidence->>'originalSourceCaptureReferenceBytesBase64','base64')), 'hex')
            OR jsonb_typeof(account_evidence->'sourceEvidenceVector') IS DISTINCT FROM 'array'
            OR jsonb_array_length(account_evidence->'sourceEvidenceVector') < 1 THEN
            RAISE EXCEPTION 'World activation fence does not retain a complete exact Account protection receipt'
                USING ERRCODE = '23514';
        END IF;
        account_request := convert_from(
            decode(account_evidence->>'requestBytesBase64', 'base64'), 'UTF8')::JSONB;
        IF jsonb_typeof(account_request) IS DISTINCT FROM 'object'
            OR (SELECT count(*) FROM jsonb_object_keys(account_request)) <> 12
            OR account_request->>'schema' IS DISTINCT FROM 'account-start-session-activation-protection-request/v1'
            OR decode(account_request->>'originalPostAuthorizationTupleBytesBase64', 'base64')
                IS DISTINCT FROM NEW.original_post_authorization_tuple
            OR account_request->>'gameSessionOwnerMutationId' IS DISTINCT FROM NEW.game_session_owner_mutation_id::TEXT
            OR account_request->>'gameSessionOwnerAttemptId' IS DISTINCT FROM NEW.game_session_owner_attempt_id::TEXT
            OR account_request->>'gameSessionOwnerFence' IS DISTINCT FROM NEW.game_session_owner_fence::TEXT
            OR (account_request->>'originalLeaseExpiresAt')::TIMESTAMPTZ
                IS DISTINCT FROM NEW.original_game_session_lease_expires_at
            OR account_request->>'accountWorldParticipationId' IS DISTINCT FROM NEW.account_world_participation_id::TEXT
            OR account_request->>'accountWorldParticipationFence' IS DISTINCT FROM NEW.account_world_participation_fence::TEXT
            OR account_request->>'worldActivationRequestId' IS DISTINCT FROM NEW.activation_request_id::TEXT
            OR decode(account_request->>'preparingEvidenceBytesBase64', 'base64')
                IS DISTINCT FROM NEW.preparing_evidence_bytes
            OR preparing->>'schema' IS DISTINCT FROM 'world-canonical-instance-lifecycle-evidence/v1'
            OR preparing->>'lifecycleStatus' IS DISTINCT FROM 'PREPARING'
            OR preparing->>'lifecycleEpoch' IS DISTINCT FROM NEW.expected_lifecycle_epoch::TEXT
            OR preparing->>'rowVersion' IS DISTINCT FROM NEW.expected_row_version::TEXT
            OR preparing->'request'->>'targetNamespace' IS DISTINCT FROM NEW.target_namespace
            OR preparing->'request'->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
            OR preparing->'request'->>'worldSlug' IS DISTINCT FROM NEW.canonical_world_slug
            OR preparing->'request'->>'controlPlaneRequestId' IS DISTINCT FROM NEW.control_plane_request_id
            OR preparing->'request'->>'canonicalGameInstanceId' IS DISTINCT FROM NEW.canonical_game_instance_id::TEXT THEN
            RAISE EXCEPTION 'World activation fence identity differs from the complete Account original request'
                USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1
            FROM world_canonical_instance_execution e
            JOIN world_canonical_instance_association a
                ON a.canonical_game_instance_id=e.canonical_game_instance_id
            JOIN world_instance w ON w.id=a.world_instance_id
            JOIN world_canonical_instance_preparation p
                ON p.canonical_game_instance_id=a.canonical_game_instance_id
            WHERE e.target_namespace=NEW.target_namespace
                AND e.canonical_tenant_id=NEW.canonical_tenant_id
                AND e.control_plane_request_id=NEW.control_plane_request_id
                AND e.canonical_world_slug=NEW.canonical_world_slug
                AND e.canonical_game_instance_id=NEW.canonical_game_instance_id
                AND e.original_post_authorization_tuple=NEW.original_post_authorization_tuple
                AND e.account_world_participation_id=NEW.account_world_participation_id
                AND e.account_world_participation_fence=NEW.account_world_participation_fence
                AND e.game_session_owner_mutation_id=NEW.game_session_owner_mutation_id
                AND e.game_session_owner_attempt_id=NEW.game_session_owner_attempt_id
                AND e.game_session_owner_fence=NEW.game_session_owner_fence
                AND e.original_game_session_lease_expires_at=NEW.original_game_session_lease_expires_at
                AND e.operation_state='COMMITTED'
                AND e.world_execution_fence=NEW.world_execution_fence
                AND e.world_instance_id=NEW.world_instance_id
                AND a.world_instance_id=NEW.world_instance_id
                AND a.schema_version=2
                AND a.canonical_realm_id=NEW.canonical_realm_id
                AND a.catalog_revision=NEW.catalog_revision
                AND w.canonical_game_instance_id=a.canonical_game_instance_id
                AND w.canonical_realm_id=a.canonical_realm_id
                AND w.catalog_revision=a.catalog_revision
                AND p.input_digest=e.preparation_input_digest
                AND p.input_digest=preparing->>'preparationInputDigest'
                AND p.input_json::JSONB->'identity'->>'canonicalRealmId'=a.canonical_realm_id::TEXT
                AND p.input_json::JSONB->'identity'->>'catalogRevision'=a.catalog_revision::TEXT
                AND p.input_json::JSONB->>'schemaVersion'='4'
        ) THEN
            RAISE EXCEPTION 'World activation fence differs from the committed original execution, schema-2 realm/catalog, or exact preparation'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.operation_state IS DISTINCT FROM 'PENDING'
        OR NEW.operation_state NOT IN ('COMMITTED', 'ABORTED')
        OR (to_jsonb(NEW) - ARRAY['operation_state','terminal_result_bytes','terminal_result_digest',
                'terminal_evidence_bytes','terminal_evidence_digest','terminal_at'])
            IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['operation_state','terminal_result_bytes','terminal_result_digest',
                'terminal_evidence_bytes','terminal_evidence_digest','terminal_at'])
        OR NEW.terminal_result_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(NEW.terminal_result_bytes), 'hex')
        OR NEW.terminal_evidence_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(NEW.terminal_evidence_bytes), 'hex')
        OR NEW.terminal_at IS NULL THEN
        RAISE EXCEPTION 'World activation intent permits one immutable PENDING-to-terminal transition only'
            USING ERRCODE = '55000';
    END IF;
    IF NEW.terminal_result_bytes IS NULL OR NEW.terminal_evidence_bytes IS NULL
        OR NEW.terminal_result_digest IS NULL OR NEW.terminal_evidence_digest IS NULL THEN
        RAISE EXCEPTION 'World activation terminal fence requires complete immutable result and evidence bytes'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_validate_original_start_session_activation_fence() FROM PUBLIC;
CREATE TRIGGER trg_world_original_start_session_activation_fence
    BEFORE INSERT OR UPDATE OR DELETE ON world_canonical_instance_activation_fence
    FOR EACH ROW EXECUTE FUNCTION world_validate_original_start_session_activation_fence();

CREATE FUNCTION world_reject_original_start_session_activation_fence_truncate()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
BEGIN
    RAISE EXCEPTION 'World original StartSession activation fences are immutable and retained'
        USING ERRCODE = '55000';
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_reject_original_start_session_activation_fence_truncate() FROM PUBLIC;
CREATE TRIGGER trg_world_original_start_session_activation_fence_no_truncate
    BEFORE TRUNCATE ON world_canonical_instance_activation_fence
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_original_start_session_activation_fence_truncate();

-- Every new V43 result, including a direct SQL insert, must belong to the exact pending protected
-- operation. The pre-existing operation rows are not rewritten or revalidated.
CREATE FUNCTION world_require_protected_canonical_activation_operation()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    fence "${serviceSchema}".world_canonical_instance_activation_fence%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World canonical activation operation evidence is immutable'
            USING ERRCODE = '55000';
    END IF;
    SELECT * INTO fence FROM "${serviceSchema}".world_canonical_instance_activation_fence
        WHERE activation_request_id=NEW.activation_request_id FOR UPDATE;
    IF NOT FOUND
        OR fence.operation_state IS DISTINCT FROM 'PENDING'
        OR fence.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
        OR fence.world_instance_id IS DISTINCT FROM NEW.world_instance_id
        OR fence.expected_lifecycle_epoch IS DISTINCT FROM NEW.expected_lifecycle_epoch
        OR fence.expected_row_version IS DISTINCT FROM NEW.expected_row_version
        OR fence.preparing_evidence_bytes IS DISTINCT FROM NEW.preparing_evidence_bytes
        OR NEW.request_bytes IS DISTINCT FROM
            decode(convert_from(NEW.result_bytes, 'UTF8')::JSONB->>'requestBytesBase64','base64')
        OR convert_from(NEW.result_bytes, 'UTF8')::JSONB->>'requestDigest' IS DISTINCT FROM NEW.request_digest THEN
        RAISE EXCEPTION 'New World activation result lacks its exact pending Account-protected operation fence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION world_require_protected_canonical_activation_operation() FROM PUBLIC;
CREATE TRIGGER trg_world_require_protected_canonical_activation_operation
    BEFORE INSERT ON world_canonical_instance_activation_operation
    FOR EACH ROW EXECUTE FUNCTION world_require_protected_canonical_activation_operation();

CREATE FUNCTION world_require_original_start_session_activation_terminal()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    result JSONB;
    terminal JSONB;
BEGIN
    IF NEW.operation_state='PENDING' THEN RETURN NULL; END IF;
    result := convert_from(NEW.terminal_result_bytes, 'UTF8')::JSONB;
    terminal := convert_from(NEW.terminal_evidence_bytes, 'UTF8')::JSONB;
    IF NOT EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_canonical_instance_activation_operation o
        WHERE o.activation_request_id=NEW.activation_request_id
            AND o.canonical_game_instance_id=NEW.canonical_game_instance_id
            AND o.world_instance_id=NEW.world_instance_id
            AND o.outcome=NEW.operation_state
            AND o.result_bytes=NEW.terminal_result_bytes
            AND o.result_digest=NEW.terminal_result_digest
            AND o.request_bytes=decode(result->>'requestBytesBase64','base64')
            AND o.preparing_evidence_bytes=NEW.preparing_evidence_bytes
    ) OR jsonb_typeof(terminal) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(terminal)) <> 8
        OR terminal->>'schema' IS DISTINCT FROM 'world-original-start-session-activation-terminal-evidence/v1'
        OR terminal->>'activationRequestId' IS DISTINCT FROM NEW.activation_request_id::TEXT
        OR terminal->>'accountProtectionId' IS DISTINCT FROM NEW.account_protection_id::TEXT
        OR terminal->>'accountProtectionFence' IS DISTINCT FROM NEW.account_protection_fence::TEXT
        OR terminal->>'worldActivationFence' IS DISTINCT FROM NEW.world_activation_fence::TEXT
        OR decode(terminal->>'accountProtectionEvidenceBytesBase64','base64')
            IS DISTINCT FROM NEW.account_protection_evidence_bytes
        OR decode(terminal->>'activationResultBytesBase64','base64')
            IS DISTINCT FROM NEW.terminal_result_bytes
        OR terminal->>'activationResultSha256' IS DISTINCT FROM NEW.terminal_result_digest
        OR result->>'activationRequestId' IS DISTINCT FROM NEW.activation_request_id::TEXT
        OR result->>'outcome' IS DISTINCT FROM NEW.operation_state THEN
        RAISE EXCEPTION 'World terminal receipt is not the exact Account protection and committed lifecycle operation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_require_original_start_session_activation_terminal() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_world_original_start_session_activation_terminal
    AFTER INSERT OR UPDATE ON world_canonical_instance_activation_fence
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION world_require_original_start_session_activation_terminal();

CREATE FUNCTION world_require_protected_activation_fence_for_operation()
RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    fence "${serviceSchema}".world_canonical_instance_activation_fence%ROWTYPE;
BEGIN
    SELECT * INTO fence FROM "${serviceSchema}".world_canonical_instance_activation_fence
        WHERE activation_request_id=NEW.activation_request_id;
    IF NOT FOUND
        OR fence.canonical_game_instance_id IS DISTINCT FROM NEW.canonical_game_instance_id
        OR fence.world_instance_id IS DISTINCT FROM NEW.world_instance_id
        OR fence.operation_state IS DISTINCT FROM NEW.outcome THEN
        RAISE EXCEPTION 'World activation lifecycle result cannot commit without its exact protected terminal fence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_require_protected_activation_fence_for_operation() FROM PUBLIC;
CREATE CONSTRAINT TRIGGER trg_world_protected_activation_operation_terminal
    AFTER INSERT ON world_canonical_instance_activation_operation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION world_require_protected_activation_fence_for_operation();
-- [jooq ignore stop]
