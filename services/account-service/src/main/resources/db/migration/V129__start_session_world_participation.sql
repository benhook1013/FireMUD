-- Account-owned participation in the distinct World StartSession execution. This is an
-- integrity fence only: it does not authenticate the World transport or enable execution.
CREATE TABLE account_start_session_world_participations (
    participation_id UUID PRIMARY KEY
        CHECK (participation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    participation_fence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE
        CHECK (participation_fence > 0),
    control_plane_request_id VARCHAR(128) NOT NULL UNIQUE
        CHECK (octet_length(control_plane_request_id) BETWEEN 1 AND 128),
    original_post_authorization_tuple BYTEA NOT NULL
        CHECK (octet_length(original_post_authorization_tuple) BETWEEN 1 AND 262144),
    target_namespace VARCHAR(63) NOT NULL
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    canonical_tenant_id UUID NOT NULL
        REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id) ON DELETE RESTRICT
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    canonical_game_instance_id UUID NOT NULL
        CHECK (canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_attempt_id UUID NOT NULL
        CHECK (game_session_owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_fence BIGINT NOT NULL CHECK (game_session_owner_fence > 0),
    preparation_input_json TEXT NOT NULL CHECK (length(preparation_input_json) > 0),
    preparation_input_digest VARCHAR(71) NOT NULL
        CHECK (preparation_input_digest ~ '^sha256:[0-9a-f]{64}$'),
    producer_xid BIGINT NOT NULL DEFAULT txid_current(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_start_session_world_participation_authorization_fk
        FOREIGN KEY (control_plane_request_id)
        REFERENCES account_start_session_operator_authorizations(control_plane_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_start_session_world_participation_capture_fk
        FOREIGN KEY (control_plane_request_id)
        REFERENCES account_start_session_authority_captures(control_plane_request_id)
        ON DELETE RESTRICT
);

CREATE TABLE account_start_session_world_participation_sources (
    participation_id UUID NOT NULL
        REFERENCES account_start_session_world_participations(participation_id) ON DELETE RESTRICT,
    source_key VARCHAR(2048) NOT NULL
        CHECK (length(source_key) > 0 AND octet_length(source_key) <= 2048)
        REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (participation_id, source_key)
);
CREATE INDEX account_start_session_world_participation_sources_by_key
    ON account_start_session_world_participation_sources(source_key, participation_id);

CREATE TABLE account_start_session_world_participation_settlements (
    participation_id UUID PRIMARY KEY
        REFERENCES account_start_session_world_participations(participation_id) ON DELETE RESTRICT,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('COMMITTED', 'ABORTED')),
    world_execution_fence BIGINT NOT NULL CHECK (world_execution_fence > 0),
    terminal_bytes BYTEA NOT NULL CHECK (octet_length(terminal_bytes) BETWEEN 1 AND 25165824),
    terminal_digest VARCHAR(71) NOT NULL CHECK (terminal_digest ~ '^sha256:[0-9a-f]{64}$'),
    settled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (terminal_digest = 'sha256:' || encode(sha256(terminal_bytes), 'hex'))
);

-- [jooq ignore start]
CREATE FUNCTION account_start_session_world_participation_source_key(evidence BYTEA)
RETURNS TEXT LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    position_value INTEGER := 1;
    parsed BYTEA;
    kind_value TEXT;
    scope_value TEXT;
    source_key_value TEXT;
BEGIN
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(evidence, position_value);
    IF convert_from(parsed, 'UTF8') IS DISTINCT FROM 'account-draft-source-evidence/v1' THEN
        RAISE EXCEPTION 'Invalid StartSession participation source schema' USING ERRCODE = '23514';
    END IF;
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(evidence, position_value);
    kind_value := convert_from(parsed, 'UTF8');
    SELECT frame_value, next_position INTO parsed, position_value
        FROM account_publication_authorization_read_frame(evidence, position_value);
    scope_value := convert_from(parsed, 'UTF8');
    source_key_value := kind_value || ':' || scope_value;
    IF length(kind_value) = 0 OR length(scope_value) = 0
        OR length(source_key_value) = 0 OR octet_length(source_key_value) > 2048 THEN
        RAISE EXCEPTION 'Invalid StartSession participation source key' USING ERRCODE = '23514';
    END IF;
    RETURN source_key_value;
END;
$$;

CREATE FUNCTION account_start_session_world_participation_validate_input(
    input_json TEXT,
    input_digest TEXT,
    original_tuple BYTEA,
    namespace_value TEXT,
    tenant_value UUID,
    request_value TEXT,
    instance_value UUID
) RETURNS VOID LANGUAGE plpgsql IMMUTABLE STRICT AS $$
DECLARE
    input_value JSONB;
    tuple_value JSONB;
    original_value JSONB;
    descriptor_value JSONB;
BEGIN
    IF length(input_json) = 0
        OR input_digest !~ '^sha256:[0-9a-f]{64}$'
        OR input_digest IS DISTINCT FROM 'sha256:' || encode(sha256(convert_to(input_json, 'UTF8')), 'hex')
        OR octet_length(original_tuple) NOT BETWEEN 1 AND 262144
        OR namespace_value !~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        OR tenant_value = '00000000-0000-0000-0000-000000000000'::UUID
        OR instance_value = '00000000-0000-0000-0000-000000000000'::UUID
        OR length(request_value) = 0 THEN
        RAISE EXCEPTION 'Malformed StartSession World participation identity or preparation input'
            USING ERRCODE = '23514';
    END IF;

    input_value := input_json::JSONB;
    tuple_value := convert_from(original_tuple, 'UTF8')::JSONB;
    original_value := tuple_value->'preAuthorizationReservationTuple';
    IF jsonb_typeof(input_value) IS DISTINCT FROM 'object'
        OR jsonb_typeof(input_value->'identity') IS DISTINCT FROM 'object'
        OR jsonb_typeof(input_value->'gameSessionReadRequest') IS DISTINCT FROM 'object'
        OR jsonb_typeof(input_value->'gameSessionReadEvidence') IS DISTINCT FROM 'object'
        OR jsonb_typeof(tuple_value) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'StartSession World preparation input or tuple is not an object'
            USING ERRCODE = '23514';
    END IF;
    descriptor_value := (input_value->'gameSessionReadEvidence'->>'descriptorJson')::JSONB;
    IF tuple_value->>'tupleSchemaId' IS DISTINCT FROM 'postAuthorizationExecutionTuple'
        OR tuple_value->>'tupleSchemaVersion' IS DISTINCT FROM '1'
        OR tuple_value->>'issuanceKind' IS DISTINCT FROM 'human_operator'
        OR tuple_value->>'controlPlaneRequestId' IS DISTINCT FROM
            original_value->'actionFamilyRequestIdentity'->>'requestId'
        OR tuple_value->>'controlPlaneRequestId' IS DISTINCT FROM request_value
        OR tuple_value->'scope'->>'targetNamespace' IS DISTINCT FROM namespace_value
        OR tuple_value->'scope'->>'tenantId' IS DISTINCT FROM tenant_value::TEXT
        OR tuple_value->'targetOwner'->>'ownerService' IS DISTINCT FROM 'game-session-service'
        OR original_value->>'actionFamily' IS DISTINCT FROM 'StartSession'
        OR (tuple_value->'target'->>'gameTemplateId')::BIGINT IS DISTINCT FROM
            (descriptor_value->>'gameTemplateId')::BIGINT
        OR input_value->'identity'->>'canonicalGameInstanceId' IS DISTINCT FROM instance_value::TEXT
        OR input_value->'gameSessionReadEvidence'->>'canonicalGameInstanceId' IS DISTINCT FROM instance_value::TEXT
        OR input_value->'gameSessionReadRequest'->>'canonicalGameInstanceId' IS DISTINCT FROM instance_value::TEXT
        OR input_value->'identity'->>'targetNamespace' IS DISTINCT FROM namespace_value
        OR input_value->'identity'->>'canonicalTenantId' IS DISTINCT FROM tenant_value::TEXT
        OR input_value->'identity'->>'controlPlaneRequestId' IS DISTINCT FROM request_value
        OR input_value->'gameSessionReadRequest'->>'targetNamespace' IS DISTINCT FROM namespace_value
        OR input_value->'gameSessionReadRequest'->>'canonicalTenantId' IS DISTINCT FROM tenant_value::TEXT
        OR input_value->'gameSessionReadRequest'->>'worldSlug' IS DISTINCT FROM
            input_value->'identity'->>'worldSlug'
        OR input_value->'gameSessionReadRequest'->>'controlPlaneRequestId' IS DISTINCT FROM request_value
        OR input_value->'identity'->>'targetNamespace' IS DISTINCT FROM
            input_value->'gameSessionReadEvidence'->>'targetNamespace'
        OR input_value->'identity'->>'canonicalTenantId' IS DISTINCT FROM
            input_value->'gameSessionReadEvidence'->>'canonicalTenantId'
        OR input_value->'identity'->>'worldSlug' IS DISTINCT FROM
            input_value->'gameSessionReadEvidence'->>'worldSlug'
        OR input_value->'identity'->>'controlPlaneRequestId' IS DISTINCT FROM
            input_value->'gameSessionReadEvidence'->>'controlPlaneRequestId'
        OR input_value->'launchBinding'->>'targetNamespace' IS DISTINCT FROM namespace_value
        OR input_value->'launchBinding'->>'canonicalTenantId' IS DISTINCT FROM tenant_value::TEXT
        OR input_value->'launchBinding'->>'worldSlug' IS DISTINCT FROM
            input_value->'identity'->>'worldSlug'
        OR input_value->'launchBinding'->>'controlPlaneRequestId' IS DISTINCT FROM request_value
        OR tuple_value->'authorityEvidenceBundle'->'accountProjectionEvidence'->>'expiresAt' IS NULL THEN
        RAISE EXCEPTION 'World preparation identity or descriptor differs from the retained StartSession tuple'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION account_start_session_world_participation_lock_sources(snapshot_value JSONB)
RETURNS TEXT[] LANGUAGE plpgsql VOLATILE STRICT AS $$
DECLARE
    source_value JSONB;
    evidence_value BYTEA;
    source_key_value TEXT;
    key_values TEXT[] := ARRAY[]::TEXT[];
    ordered_values TEXT[];
BEGIN
    IF jsonb_typeof(snapshot_value->'sourceVector') IS DISTINCT FROM 'array'
        OR jsonb_array_length(snapshot_value->'sourceVector') < 1 THEN
        RAISE EXCEPTION 'StartSession participation requires a complete retained source vector'
            USING ERRCODE = '23514';
    END IF;
    FOR source_value IN SELECT value FROM jsonb_array_elements(snapshot_value->'sourceVector') LOOP
        IF jsonb_typeof(source_value) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'StartSession source vector entries must be Base64 strings' USING ERRCODE = '23514';
        END IF;
        evidence_value := decode(source_value #>> '{}', 'base64');
        source_key_value := account_start_session_world_participation_source_key(evidence_value);
        IF source_key_value = ANY(key_values) THEN
            RAISE EXCEPTION 'Duplicate StartSession participation source key' USING ERRCODE = '23514';
        END IF;
        key_values := array_append(key_values, source_key_value);
    END LOOP;
    SELECT array_agg(value ORDER BY account_publication_authorization_source_sort_key(value))
        INTO ordered_values FROM unnest(key_values) AS keys(value);
    IF key_values IS DISTINCT FROM ordered_values THEN
        RAISE EXCEPTION 'StartSession retained source vector is not in canonical source-key order'
            USING ERRCODE = '23514';
    END IF;

    FOR source_key_value IN
        SELECT value FROM unnest(key_values) AS keys(value)
        ORDER BY account_publication_authorization_source_sort_key(value)
    LOOP
        PERFORM source_key FROM account_draft_authorization_source_locks
            WHERE source_key = source_key_value FOR UPDATE NOWAIT;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'StartSession participation source lock is not provisioned'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN key_values;
END;
$$;

CREATE FUNCTION account_start_session_world_participation_assert_exclusive(
    source_keys TEXT[], snapshot_value JSONB
)
RETURNS VOID LANGUAGE plpgsql STABLE STRICT AS $$
DECLARE
    source_value JSONB;
    evidence_value BYTEA;
    source_key_value TEXT;
BEGIN
    IF EXISTS (
        SELECT 1 FROM account_draft_authorization_changed_scopes changed
        JOIN account_draft_authorization_source_changes change USING (change_id)
        WHERE changed.source_key = ANY(source_keys) AND change.status = 'WAITING'
    ) THEN
        RAISE EXCEPTION 'StartSession participation cannot acquire a source with a waiting source change'
            USING ERRCODE = '55P03';
    END IF;
    -- Match the exact captured hosted-terms evidence, as the existing Draft/publication
    -- admission boundary does. An older DISCLOSED record or a shared role/source key
    -- does not establish disclosure of this captured hosted-terms source version.
    FOR source_value IN SELECT value FROM jsonb_array_elements(snapshot_value->'sourceVector') LOOP
        evidence_value := decode(source_value #>> '{}', 'base64');
        source_key_value := account_start_session_world_participation_source_key(evidence_value);
        IF source_key_value LIKE 'HOSTED_TERMS:%' AND EXISTS (
            SELECT 1 FROM account_hosted_terms_disclosure_handoffs handoff
            JOIN account_hosted_terms_disclosure_sources handoff_source
                ON handoff_source.handoff_id = handoff.handoff_id
            WHERE handoff.source_key = source_key_value
                AND handoff_source.source_key = handoff.source_key
                AND handoff_source.source_evidence = evidence_value
                AND handoff.status IN ('DISPATCH_AUTHORIZED', 'AMBIGUOUS', 'DISCLOSED')
        ) THEN
            RAISE EXCEPTION 'StartSession participation cannot acquire an exactly disclosed hosted-terms source'
                USING ERRCODE = '55P03';
        END IF;
    END LOOP;
END;
$$;

CREATE FUNCTION account_start_session_world_participation_assert_sources(
    participation_value UUID,
    snapshot_value JSONB
) RETURNS VOID LANGUAGE plpgsql STABLE STRICT AS $$
DECLARE
    source_value JSONB;
    evidence_value BYTEA;
    source_key_value TEXT;
    key_values TEXT[] := ARRAY[]::TEXT[];
    ordered_values TEXT[];
    source_count INTEGER;
BEGIN
    IF jsonb_typeof(snapshot_value->'sourceVector') IS DISTINCT FROM 'array'
        OR jsonb_array_length(snapshot_value->'sourceVector') < 1 THEN
        RAISE EXCEPTION 'StartSession participation source vector is absent' USING ERRCODE = '23514';
    END IF;
    source_count := jsonb_array_length(snapshot_value->'sourceVector');
    IF source_count IS DISTINCT FROM (
        SELECT count(*)::INTEGER FROM account_start_session_world_participation_sources
        WHERE participation_id = participation_value) THEN
        RAISE EXCEPTION 'StartSession participation has incomplete or extra source children'
            USING ERRCODE = '23514';
    END IF;
    FOR source_value IN SELECT value FROM jsonb_array_elements(snapshot_value->'sourceVector') LOOP
        IF jsonb_typeof(source_value) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'StartSession source vector entries must be Base64 strings' USING ERRCODE = '23514';
        END IF;
        evidence_value := decode(source_value #>> '{}', 'base64');
        source_key_value := account_start_session_world_participation_source_key(evidence_value);
        IF source_key_value = ANY(key_values)
            OR NOT EXISTS (
                SELECT 1 FROM account_start_session_world_participation_sources source
                WHERE source.participation_id = participation_value
                    AND source.source_key = source_key_value
                    AND source.source_evidence = evidence_value) THEN
            RAISE EXCEPTION 'StartSession source child differs from the exact captured source vector'
                USING ERRCODE = '23514';
        END IF;
        key_values := array_append(key_values, source_key_value);
    END LOOP;
    SELECT array_agg(value ORDER BY account_publication_authorization_source_sort_key(value))
        INTO ordered_values FROM unnest(key_values) AS keys(value);
    IF key_values IS DISTINCT FROM ordered_values THEN
        RAISE EXCEPTION 'StartSession captured source vector is not in canonical source-key order'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION account_start_session_world_participation_is_settled(participation_value UUID)
RETURNS BOOLEAN LANGUAGE SQL STABLE STRICT AS $$
    SELECT EXISTS (
        SELECT 1 FROM account_start_session_world_participation_settlements settlement
        WHERE settlement.participation_id = participation_value)
$$;

CREATE FUNCTION account_start_session_world_participation_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    authorization_row account_start_session_operator_authorizations%ROWTYPE;
    capture account_start_session_authority_captures%ROWTYPE;
    tuple_value JSONB;
    reservation_value JSONB;
    bundle_reference JSONB;
    snapshot_value JSONB;
    source_keys TEXT[];
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'StartSession World participation is append-only' USING ERRCODE = '23514';
    END IF;
    IF NEW.producer_xid IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'StartSession World participation must be created by its producer transaction'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT capture FROM account_start_session_authority_captures
        WHERE control_plane_request_id = NEW.control_plane_request_id;
    snapshot_value := convert_from(capture.canonical_snapshot_bytes, 'UTF8')::JSONB;
    IF snapshot_value->>'schema' IS DISTINCT FROM 'account-start-session-authority-snapshot/v1'
        OR snapshot_value->>'controlPlaneRequestId' IS DISTINCT FROM NEW.control_plane_request_id THEN
        RAISE EXCEPTION 'StartSession World participation must retain the exact Account capture'
            USING ERRCODE = '23514';
    END IF;
    source_keys := account_start_session_world_participation_lock_sources(snapshot_value);
    PERFORM account_start_session_world_participation_assert_exclusive(source_keys, snapshot_value);

    SELECT * INTO STRICT authorization_row FROM account_start_session_operator_authorizations
        WHERE control_plane_request_id = NEW.control_plane_request_id;
    tuple_value := convert_from(NEW.original_post_authorization_tuple, 'UTF8')::JSONB;
    IF authorization_row.status IS DISTINCT FROM 'REDEEMED'
        OR authorization_row.reference_expires_at <= clock_timestamp()
        OR tuple_value->'preAuthorizationReservationTuple' IS DISTINCT FROM
            convert_from(authorization_row.pre_authorization_tuple, 'UTF8')::JSONB
        OR authorization_row.redemption_owner_attempt_id IS DISTINCT FROM NEW.game_session_owner_attempt_id
        OR authorization_row.redemption_owner_fence IS DISTINCT FROM NEW.game_session_owner_fence THEN
        RAISE EXCEPTION 'StartSession World participation requires the exact unexpired redeemed original authorization'
            USING ERRCODE = '23514';
    END IF;
    reservation_value := tuple_value->'preAuthorizationReservationTuple';
    bundle_reference := tuple_value->'authorityEvidenceBundleReference';
    IF tuple_value->>'tupleSchemaId' IS DISTINCT FROM 'postAuthorizationExecutionTuple'
        OR tuple_value->>'tupleSchemaVersion' IS DISTINCT FROM '1'
        OR tuple_value->>'issuanceKind' IS DISTINCT FROM 'human_operator'
        OR tuple_value->>'controlPlaneRequestId' IS DISTINCT FROM NEW.control_plane_request_id
        OR tuple_value->>'mutationDigest' IS DISTINCT FROM authorization_row.mutation_digest
        OR tuple_value->>'authorizationReferenceFingerprint' IS DISTINCT FROM
            authorization_row.authorization_reference_fingerprint
        OR tuple_value->>'issuanceFence' IS DISTINCT FROM authorization_row.issuance_fence::TEXT
        OR tuple_value->>'reservationOwnerId' IS DISTINCT FROM authorization_row.reservation_owner_id::TEXT
        OR tuple_value->>'reservationClaimFence' IS DISTINCT FROM authorization_row.reservation_claim_fence::TEXT
        OR tuple_value->>'authorityEvidenceBundleReference' IS NULL
        OR bundle_reference->>'bundleVersion' IS DISTINCT FROM authorization_row.bundle_version
        OR bundle_reference->>'sourceVersion' IS DISTINCT FROM authorization_row.bundle_source_version
        OR bundle_reference->>'sourceFence' IS DISTINCT FROM authorization_row.bundle_source_fence
        OR bundle_reference->>'linearization' IS DISTINCT FROM authorization_row.bundle_linearization
        OR tuple_value->'authorityEvidenceBundle' IS DISTINCT FROM
            convert_from(authorization_row.authority_evidence_bundle, 'UTF8')::JSONB
        OR authorization_row.redemption_reference_fingerprint IS DISTINCT FROM
            authorization_row.authorization_reference_fingerprint
        OR authorization_row.redemption_authority_evidence_bundle IS DISTINCT FROM
            authorization_row.authority_evidence_bundle
        OR capture.pre_authorization_tuple IS DISTINCT FROM authorization_row.pre_authorization_tuple
        OR capture.account_uuid IS DISTINCT FROM (tuple_value->'actor'->>'accountId')::UUID
        OR capture.tenant_uuid IS DISTINCT FROM NEW.canonical_tenant_id
        OR capture.target_owner IS DISTINCT FROM tuple_value->'targetOwner'->>'ownerService'
        OR capture.mutation_digest IS DISTINCT FROM authorization_row.mutation_digest
        OR capture.reservation_owner_id IS DISTINCT FROM authorization_row.reservation_owner_id
        OR capture.reservation_claim_fence IS DISTINCT FROM authorization_row.reservation_claim_fence
        OR capture.issuance_fence IS DISTINCT FROM authorization_row.issuance_fence
        OR capture.source_version::TEXT IS DISTINCT FROM bundle_reference->>'sourceVersion'
        OR capture.source_fence::TEXT IS DISTINCT FROM bundle_reference->>'sourceFence'
        OR capture.linearization IS DISTINCT FROM bundle_reference->>'linearization'
        OR tuple_value->'scope'->>'targetNamespace' IS DISTINCT FROM NEW.target_namespace
        OR tuple_value->'scope'->>'tenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR reservation_value->>'actionFamily' IS DISTINCT FROM 'StartSession'
        OR reservation_value->'actionFamilyRequestIdentity'->>'requestId' IS DISTINCT FROM
            NEW.control_plane_request_id
        OR tuple_value->'targetOwner'->>'ownerService' IS DISTINCT FROM 'game-session-service' THEN
        RAISE EXCEPTION 'StartSession World participation differs from its original Account authorization or capture'
            USING ERRCODE = '23514';
    END IF;
    PERFORM account_start_session_world_participation_validate_input(
        NEW.preparation_input_json, NEW.preparation_input_digest,
        NEW.original_post_authorization_tuple, NEW.target_namespace,
        NEW.canonical_tenant_id, NEW.control_plane_request_id,
        NEW.canonical_game_instance_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_start_session_world_participation_source_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent_xid BIGINT;
BEGIN
    SELECT producer_xid INTO parent_xid FROM account_start_session_world_participations
        WHERE participation_id = NEW.participation_id;
    IF parent_xid IS DISTINCT FROM txid_current()
        OR account_start_session_world_participation_source_key(NEW.source_evidence) IS DISTINCT FROM NEW.source_key THEN
        RAISE EXCEPTION 'StartSession sources must accompany the exact original participation transaction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_start_session_world_participation_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_start_session_world_participations%ROWTYPE;
    capture account_start_session_authority_captures%ROWTYPE;
    snapshot_value JSONB;
    source_keys TEXT[];
BEGIN
    SELECT * INTO STRICT original FROM account_start_session_world_participations
        WHERE participation_id = NEW.participation_id;
    IF original.producer_xid IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'StartSession participation completion must run in its original transaction'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT capture FROM account_start_session_authority_captures
        WHERE control_plane_request_id = original.control_plane_request_id;
    snapshot_value := convert_from(capture.canonical_snapshot_bytes, 'UTF8')::JSONB;
    source_keys := account_start_session_world_participation_lock_sources(snapshot_value);
    PERFORM account_start_session_world_participation_assert_exclusive(source_keys, snapshot_value);
    PERFORM account_start_session_world_participation_assert_sources(original.participation_id, snapshot_value);
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_start_session_world_participation_terminal_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_start_session_world_participations%ROWTYPE;
    terminal_value JSONB;
    terminal_field_count INTEGER;
    source_key_value TEXT;
    encoded_tuple TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'StartSession World participation settlement is append-only' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT original FROM account_start_session_world_participations
        WHERE participation_id = NEW.participation_id;
    FOR source_key_value IN SELECT source_key
        FROM account_start_session_world_participation_sources
        WHERE participation_id = NEW.participation_id
        ORDER BY account_publication_authorization_source_sort_key(source_key)
    LOOP
        PERFORM source_key FROM account_draft_authorization_source_locks
            WHERE source_key = source_key_value FOR UPDATE NOWAIT;
    END LOOP;
    PERFORM participation_id FROM account_start_session_world_participations
        WHERE participation_id = NEW.participation_id FOR UPDATE NOWAIT;

    IF NEW.terminal_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.terminal_bytes), 'hex') THEN
        RAISE EXCEPTION 'World terminal digest does not bind the exact receipt bytes' USING ERRCODE = '23514';
    END IF;
    terminal_value := convert_from(NEW.terminal_bytes, 'UTF8')::JSONB;
    encoded_tuple := regexp_replace(encode(original.original_post_authorization_tuple, 'base64'), E'[\n\r]', '', 'g');
    IF jsonb_typeof(terminal_value) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'World terminal must be a closed JSON object' USING ERRCODE = '23514';
    END IF;
    SELECT count(*)::INTEGER INTO terminal_field_count FROM jsonb_object_keys(terminal_value);
    IF terminal_field_count IS DISTINCT FROM 14 THEN
        RAISE EXCEPTION 'World terminal must contain exactly fourteen fields' USING ERRCODE = '23514';
    END IF;
    IF jsonb_typeof(terminal_value->'schema') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'originalPostAuthorizationTupleBase64') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'accountWorldParticipationId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'accountWorldParticipationFence') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'gameSessionOwnerAttemptId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'gameSessionOwnerFence') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'targetNamespace') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'canonicalTenantId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'controlPlaneRequestId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'canonicalGameInstanceId') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'preparationInputDigest') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'preparationInputJson') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'worldExecutionFence') IS DISTINCT FROM 'string'
        OR jsonb_typeof(terminal_value->'outcome') IS DISTINCT FROM 'string'
        OR terminal_value->>'schema' IS DISTINCT FROM 'world-start-session-execution-terminal/v1'
        OR terminal_value->>'originalPostAuthorizationTupleBase64' IS DISTINCT FROM encoded_tuple
        OR terminal_value->>'accountWorldParticipationId' IS DISTINCT FROM original.participation_id::TEXT
        OR terminal_value->>'accountWorldParticipationFence' IS DISTINCT FROM original.participation_fence::TEXT
        OR terminal_value->>'gameSessionOwnerAttemptId' IS DISTINCT FROM original.game_session_owner_attempt_id::TEXT
        OR terminal_value->>'gameSessionOwnerFence' IS DISTINCT FROM original.game_session_owner_fence::TEXT
        OR terminal_value->>'targetNamespace' IS DISTINCT FROM original.target_namespace
        OR terminal_value->>'canonicalTenantId' IS DISTINCT FROM original.canonical_tenant_id::TEXT
        OR terminal_value->>'controlPlaneRequestId' IS DISTINCT FROM original.control_plane_request_id
        OR terminal_value->>'canonicalGameInstanceId' IS DISTINCT FROM original.canonical_game_instance_id::TEXT
        OR terminal_value->>'preparationInputDigest' IS DISTINCT FROM original.preparation_input_digest
        OR terminal_value->>'preparationInputJson' IS DISTINCT FROM original.preparation_input_json
        OR terminal_value->>'worldExecutionFence' !~ '^[1-9][0-9]{0,18}$'
        OR (terminal_value->>'worldExecutionFence')::NUMERIC > 9223372036854775807
        OR (terminal_value->>'worldExecutionFence')::BIGINT IS DISTINCT FROM NEW.world_execution_fence
        OR terminal_value->>'outcome' IS DISTINCT FROM NEW.outcome THEN
        RAISE EXCEPTION 'World terminal is not the exact closed StartSession participation receipt'
            USING ERRCODE = '23514';
    END IF;
    PERFORM account_start_session_world_participation_validate_input(
        original.preparation_input_json, original.preparation_input_digest,
        original.original_post_authorization_tuple, original.target_namespace,
        original.canonical_tenant_id, original.control_plane_request_id,
        original.canonical_game_instance_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_start_session_world_participation_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'StartSession World participation evidence is append-only' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_start_session_world_participation_validate
    BEFORE INSERT ON account_start_session_world_participations
    FOR EACH ROW EXECUTE FUNCTION account_start_session_world_participation_insert_guard();
CREATE TRIGGER account_start_session_world_participation_immutable
    BEFORE UPDATE OR DELETE ON account_start_session_world_participations
    FOR EACH ROW EXECUTE FUNCTION account_start_session_world_participation_no_mutation();
CREATE TRIGGER account_start_session_world_participation_no_truncate
    BEFORE TRUNCATE ON account_start_session_world_participations
    FOR EACH STATEMENT EXECUTE FUNCTION account_start_session_world_participation_no_mutation();
CREATE TRIGGER account_start_session_world_participation_sources_insert
    BEFORE INSERT ON account_start_session_world_participation_sources
    FOR EACH ROW EXECUTE FUNCTION account_start_session_world_participation_source_insert_guard();
CREATE TRIGGER account_start_session_world_participation_sources_immutable
    BEFORE UPDATE OR DELETE ON account_start_session_world_participation_sources
    FOR EACH ROW EXECUTE FUNCTION account_start_session_world_participation_no_mutation();
CREATE TRIGGER account_start_session_world_participation_sources_no_truncate
    BEFORE TRUNCATE ON account_start_session_world_participation_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_start_session_world_participation_no_mutation();
CREATE CONSTRAINT TRIGGER account_start_session_world_participation_complete
    AFTER INSERT ON account_start_session_world_participations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_start_session_world_participation_complete_guard();
CREATE TRIGGER account_start_session_world_participation_settlement_validate
    BEFORE INSERT ON account_start_session_world_participation_settlements
    FOR EACH ROW EXECUTE FUNCTION account_start_session_world_participation_terminal_guard();
CREATE TRIGGER account_start_session_world_participation_settlements_immutable
    BEFORE UPDATE OR DELETE ON account_start_session_world_participation_settlements
    FOR EACH ROW EXECUTE FUNCTION account_start_session_world_participation_no_mutation();
CREATE TRIGGER account_start_session_world_settlement_no_truncate
    BEFORE TRUNCATE ON account_start_session_world_participation_settlements
    FOR EACH STATEMENT EXECUTE FUNCTION account_start_session_world_participation_no_mutation();
-- [jooq ignore stop]

-- Extend, rather than replace with an older migration body, the current V91/V93/V95/V96/V97
-- assembled source-writer decision point. The source-key lock order already matches all owners.
-- [jooq ignore start]
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_draft_authorization_sources s$anchor$;
    participation_guard TEXT := $guard$    PERFORM participation.participation_id
        FROM account_start_session_world_participations participation
        JOIN account_start_session_world_participation_sources source
            ON source.participation_id = participation.participation_id
        WHERE source.source_key = ANY(source_keys)
            AND NOT account_start_session_world_participation_is_settled(participation.participation_id)
        ORDER BY participation.participation_id FOR UPDATE OF participation NOWAIT;
    IF FOUND THEN
        RAISE EXCEPTION 'StartSession World participation remains pending for a required source'
            USING ERRCODE = '55P03';
    END IF;
$guard$;
BEGIN
    SELECT pg_get_functiondef('account_control_ui_hold_required_sources(TEXT[])'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one assembled Account Draft source-writer anchor';
    END IF;
    EXECUTE replace(original, anchor, participation_guard || anchor);
END;
$migration$;

-- Pending World participation also excludes the existing source-change terminal writer. Keep
-- its current V91+ body and insert the check only after that body has locked its sorted sources.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    FOR operation IN SELECT DISTINCT source.operation_id
        FROM account_draft_authorization_changed_scopes changed$anchor$;
    participation_guard TEXT := $guard$    IF EXISTS (
        SELECT 1 FROM account_draft_authorization_changed_scopes changed
        JOIN account_start_session_world_participation_sources source
            ON source.source_key = changed.source_key
        JOIN account_start_session_world_participations participation
            ON participation.participation_id = source.participation_id
        WHERE changed.change_id = OLD.change_id
            AND NOT account_start_session_world_participation_is_settled(participation.participation_id)
    ) THEN
        PERFORM participation.participation_id
            FROM account_draft_authorization_changed_scopes changed
            JOIN account_start_session_world_participation_sources source
                ON source.source_key = changed.source_key
            JOIN account_start_session_world_participations participation
                ON participation.participation_id = source.participation_id
            WHERE changed.change_id = OLD.change_id
                AND NOT account_start_session_world_participation_is_settled(participation.participation_id)
            ORDER BY participation.participation_id FOR UPDATE OF participation NOWAIT;
        RAISE EXCEPTION 'Pending StartSession World participation excludes source-change completion'
            USING ERRCODE = '55P03';
    END IF;
$guard$;
BEGIN
    SELECT pg_get_functiondef('account_draft_authorization_source_change_guard()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one current Account source-change terminal anchor';
    END IF;
    EXECUTE replace(original, anchor, participation_guard || anchor);
END;
$migration$;

-- A disclosure dispatch or retry is excluded by every overlapping pending World participation.
-- The repository already takes these source locks before updating the handoff row; NOWAIT keeps
-- raw or incorrectly ordered paths from waiting in an inverse lock order.
DO $migration$
DECLARE
    original TEXT;
    anchor TEXT := $anchor$    IF OLD.status = 'PREPARED' AND NEW.status = 'DISPATCH_AUTHORIZED'$anchor$;
    participation_guard TEXT := $guard$    IF OLD.status IN ('PREPARED', 'AMBIGUOUS')
        AND NEW.status = 'DISPATCH_AUTHORIZED' THEN
        PERFORM source_lock.source_key
            FROM account_draft_authorization_source_locks source_lock
            JOIN (
                SELECT OLD.source_key AS source_key
                UNION
                SELECT handoff_source.source_key
                FROM account_hosted_terms_disclosure_sources handoff_source
                WHERE handoff_source.handoff_id = OLD.handoff_id
            ) exact_source USING (source_key)
            ORDER BY account_publication_authorization_source_sort_key(source_lock.source_key)
            FOR UPDATE OF source_lock NOWAIT;
        IF EXISTS (
            SELECT 1 FROM account_start_session_world_participations participation
            JOIN account_start_session_world_participation_sources source
                ON source.participation_id = participation.participation_id
            WHERE source.source_key IN (
                SELECT OLD.source_key
                UNION
                SELECT handoff_source.source_key
                FROM account_hosted_terms_disclosure_sources handoff_source
                WHERE handoff_source.handoff_id = OLD.handoff_id)
                AND NOT account_start_session_world_participation_is_settled(participation.participation_id)
        ) THEN
            PERFORM participation.participation_id
                FROM account_start_session_world_participations participation
                JOIN account_start_session_world_participation_sources source
                    ON source.participation_id = participation.participation_id
                WHERE source.source_key IN (
                    SELECT OLD.source_key
                    UNION
                    SELECT handoff_source.source_key
                    FROM account_hosted_terms_disclosure_sources handoff_source
                    WHERE handoff_source.handoff_id = OLD.handoff_id)
                    AND NOT account_start_session_world_participation_is_settled(participation.participation_id)
                ORDER BY participation.participation_id FOR UPDATE OF participation NOWAIT;
            RAISE EXCEPTION 'Pending StartSession World participation excludes hosted-terms dispatch'
                USING ERRCODE = '55P03';
        END IF;
    END IF;
$guard$;
BEGIN
    SELECT pg_get_functiondef('account_hosted_terms_disclosure_handoff_guard()'::REGPROCEDURE)
        INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one current hosted-terms dispatch transition anchor';
    END IF;
    EXECUTE replace(original, anchor, participation_guard || anchor);
END;
$migration$;
-- [jooq ignore stop]
