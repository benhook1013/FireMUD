-- Distinct Account source protection through original Game Session admission. World
-- settlement and original-attempt observation expiry never settle this participation.
-- These integrity guards do not authenticate a producer or Game Session terminal RPC.
CREATE TABLE account_start_session_admission_protections (
    protection_id UUID PRIMARY KEY CHECK (protection_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    protection_fence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE CHECK (protection_fence > 0),
    control_plane_request_id VARCHAR(128) NOT NULL UNIQUE
        REFERENCES account_start_session_authority_captures(control_plane_request_id) ON DELETE RESTRICT,
    original_post_authorization_tuple BYTEA NOT NULL CHECK (octet_length(original_post_authorization_tuple) BETWEEN 1 AND 262144),
    account_redemption_projection BYTEA NOT NULL CHECK (octet_length(account_redemption_projection) BETWEEN 1 AND 262144),
    game_session_owner_mutation_id UUID NOT NULL UNIQUE CHECK (game_session_owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_attempt_id UUID NOT NULL CHECK (game_session_owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_fence BIGINT NOT NULL CHECK (game_session_owner_fence > 0),
    original_lease_expires_at TIMESTAMPTZ NOT NULL CHECK (isfinite(original_lease_expires_at)),
    account_world_participation_id UUID NOT NULL UNIQUE
        REFERENCES account_start_session_world_participations(participation_id) ON DELETE RESTRICT,
    account_world_participation_fence BIGINT NOT NULL CHECK (account_world_participation_fence > 0),
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    canonical_tenant_id UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id) ON DELETE RESTRICT,
    canonical_game_instance_id UUID NOT NULL CHECK (canonical_game_instance_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    capture_source_version BIGINT NOT NULL CHECK (capture_source_version > 0),
    capture_source_fence BIGINT NOT NULL CHECK (capture_source_fence > 0),
    capture_sha256 VARCHAR(64) NOT NULL CHECK (capture_sha256 ~ '^[0-9a-f]{64}$'),
    world_admission_hold_identity_bytes BYTEA NOT NULL CHECK (octet_length(world_admission_hold_identity_bytes) BETWEEN 1 AND 262144),
    request_binding_bytes BYTEA NOT NULL CHECK (octet_length(request_binding_bytes) BETWEEN 1 AND 1048576),
    request_binding_digest VARCHAR(71) NOT NULL
        CHECK (request_binding_digest = 'sha256:' || encode(sha256(request_binding_bytes), 'hex')),
    producer_xid BIGINT NOT NULL DEFAULT txid_current(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE account_start_session_admission_protection_sources (
    protection_id UUID NOT NULL REFERENCES account_start_session_admission_protections(protection_id) ON DELETE RESTRICT,
    source_key VARCHAR(2048) NOT NULL REFERENCES account_draft_authorization_source_locks(source_key),
    source_evidence BYTEA NOT NULL CHECK (octet_length(source_evidence) BETWEEN 1 AND 131072),
    PRIMARY KEY (protection_id, source_key)
);
CREATE INDEX account_ss_admission_sources_by_key
    ON account_start_session_admission_protection_sources(source_key, protection_id);

CREATE TABLE account_start_session_admission_protection_settlements (
    protection_id UUID PRIMARY KEY REFERENCES account_start_session_admission_protections(protection_id) ON DELETE RESTRICT,
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('COMMITTED', 'ABORTED')),
    terminal_bytes BYTEA NOT NULL CHECK (octet_length(terminal_bytes) BETWEEN 1 AND 25165824),
    terminal_digest VARCHAR(71) NOT NULL CHECK (terminal_digest = 'sha256:' || encode(sha256(terminal_bytes), 'hex')),
    settled_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- [jooq ignore start]
-- PostgreSQL enforces exact retained millisecond precision; jOOQ's DDL simulator
-- does not evaluate this date_trunc part. Keep the column and table visible.
ALTER TABLE account_start_session_admission_protections
    ADD CONSTRAINT account_ss_admission_original_lease_millis
        CHECK (original_lease_expires_at = date_trunc('milliseconds', original_lease_expires_at));

CREATE FUNCTION account_ss_admission_is_settled(protection_value UUID)
RETURNS BOOLEAN LANGUAGE SQL STABLE STRICT AS $$
    SELECT EXISTS (SELECT 1 FROM account_start_session_admission_protection_settlements
        WHERE protection_id = protection_value)
$$;

-- Called only after the owning writer has locked its canonical source-key set. This
-- shared addition preserves the independently assembled World and other owner guards.
CREATE FUNCTION account_ss_admission_assert_no_pending(source_keys TEXT[])
RETURNS VOID LANGUAGE plpgsql VOLATILE STRICT AS $$
BEGIN
    PERFORM protection.protection_id
        FROM account_start_session_admission_protections protection
        JOIN account_start_session_admission_protection_sources source
            ON source.protection_id = protection.protection_id
        WHERE source.source_key = ANY(source_keys)
            AND NOT account_ss_admission_is_settled(protection.protection_id)
        ORDER BY protection.protection_id FOR UPDATE OF protection NOWAIT;
    IF FOUND THEN
        RAISE EXCEPTION 'Original StartSession admission protection remains pending for a required source'
            USING ERRCODE = '55P03';
    END IF;
END;
$$;

-- Account currentness must independently reconstruct the exact live original actor,
-- registry, signer and source capture before invoking this integrity boundary. SQL
-- validates retained bindings; neither a capture nor a historical observation grants
-- admission, and a gap requires the producer's fresh unchanged-source validation.
CREATE FUNCTION account_ss_admission_assert_current(
    original account_start_session_admission_protections,
    require_complete BOOLEAN
) RETURNS VOID LANGUAGE plpgsql VOLATILE STRICT AS $$
DECLARE
    capture account_start_session_authority_captures%ROWTYPE;
    world_parent account_start_session_world_participations%ROWTYPE;
    observation account_start_session_world_original_attempt_evidence%ROWTYPE;
    authorization_row account_start_session_operator_authorizations%ROWTYPE;
    snapshot_value JSONB;
    tuple_value JSONB;
    request_value JSONB;
    hold_value JSONB;
    hold_request JSONB;
    projection_value JSONB;
    source_value JSONB;
    source_bytes BYTEA;
    source_key_value TEXT;
    keys TEXT[];
BEGIN
    SELECT * INTO STRICT capture FROM account_start_session_authority_captures
        WHERE control_plane_request_id = original.control_plane_request_id;
    snapshot_value := convert_from(capture.canonical_snapshot_bytes, 'UTF8')::JSONB;
    IF snapshot_value->>'schema' IS DISTINCT FROM 'account-start-session-authority-snapshot/v1'
        OR snapshot_value->>'controlPlaneRequestId' IS DISTINCT FROM original.control_plane_request_id THEN
        RAISE EXCEPTION 'Admission protection requires the exact original source snapshot' USING ERRCODE = '23514';
    END IF;
    keys := account_start_session_world_participation_lock_sources(snapshot_value);
    PERFORM account_start_session_world_participation_assert_exclusive(keys, snapshot_value);
    SELECT * INTO STRICT capture FROM account_start_session_authority_captures
        WHERE control_plane_request_id = original.control_plane_request_id FOR UPDATE NOWAIT;
    SELECT * INTO STRICT world_parent FROM account_start_session_world_participations
        WHERE participation_id = original.account_world_participation_id FOR UPDATE NOWAIT;
    SELECT * INTO STRICT observation FROM account_start_session_world_original_attempt_evidence
        WHERE participation_id = original.account_world_participation_id FOR UPDATE NOWAIT;
    SELECT * INTO STRICT authorization_row FROM account_start_session_operator_authorizations
        WHERE control_plane_request_id = original.control_plane_request_id FOR UPDATE NOWAIT;
    PERFORM protection_id FROM account_start_session_admission_protections
        WHERE protection_id = original.protection_id FOR UPDATE NOWAIT;
    -- Always check actual database time after every lock, including exact recovery.
    IF original.original_lease_expires_at <= clock_timestamp()
        OR authorization_row.reference_expires_at <= clock_timestamp()
        OR account_ss_admission_is_settled(original.protection_id) THEN
        RAISE EXCEPTION 'Original StartSession admission protection is not current'
            USING ERRCODE = '23514';
    END IF;
    IF capture.source_version IS DISTINCT FROM original.capture_source_version
        OR capture.source_fence IS DISTINCT FROM original.capture_source_fence
        OR capture.canonical_sha256 IS DISTINCT FROM original.capture_sha256
        OR world_parent.participation_fence IS DISTINCT FROM original.account_world_participation_fence
        OR world_parent.control_plane_request_id IS DISTINCT FROM original.control_plane_request_id
        OR world_parent.original_post_authorization_tuple IS DISTINCT FROM original.original_post_authorization_tuple
        OR world_parent.game_session_owner_attempt_id IS DISTINCT FROM original.game_session_owner_attempt_id
        OR world_parent.game_session_owner_fence IS DISTINCT FROM original.game_session_owner_fence
        OR world_parent.target_namespace IS DISTINCT FROM original.target_namespace
        OR world_parent.canonical_tenant_id IS DISTINCT FROM original.canonical_tenant_id
        OR world_parent.canonical_game_instance_id IS DISTINCT FROM original.canonical_game_instance_id
        OR observation.target_namespace IS DISTINCT FROM original.target_namespace
        OR observation.game_session_owner_mutation_id IS DISTINCT FROM original.game_session_owner_mutation_id
        OR observation.game_session_owner_attempt_id IS DISTINCT FROM original.game_session_owner_attempt_id
        OR observation.game_session_owner_fence IS DISTINCT FROM original.game_session_owner_fence
        OR observation.original_lease_expires_at IS DISTINCT FROM original.original_lease_expires_at
        OR authorization_row.status IS DISTINCT FROM 'REDEEMED'
        OR authorization_row.redemption_owner_attempt_id IS DISTINCT FROM original.game_session_owner_attempt_id
        OR authorization_row.redemption_owner_fence IS DISTINCT FROM original.game_session_owner_fence
        OR authorization_row.redemption_reference_fingerprint IS DISTINCT FROM authorization_row.authorization_reference_fingerprint
        OR authorization_row.redemption_authority_evidence_bundle IS DISTINCT FROM authorization_row.authority_evidence_bundle
        OR capture.pre_authorization_tuple IS DISTINCT FROM authorization_row.pre_authorization_tuple
        OR capture.issuance_fence IS DISTINCT FROM authorization_row.issuance_fence THEN
        RAISE EXCEPTION 'Original StartSession admission protection differs from its retained owner bindings'
            USING ERRCODE = '23514';
    END IF;
    tuple_value := convert_from(original.original_post_authorization_tuple, 'UTF8')::JSONB;
    IF tuple_value->'preAuthorizationReservationTuple' IS DISTINCT FROM convert_from(authorization_row.pre_authorization_tuple, 'UTF8')::JSONB
        OR tuple_value->>'authorizationReferenceFingerprint' IS DISTINCT FROM authorization_row.authorization_reference_fingerprint
        OR tuple_value->>'issuanceFence' IS DISTINCT FROM authorization_row.issuance_fence::TEXT
        OR tuple_value->'authorityEvidenceBundle' IS DISTINCT FROM convert_from(authorization_row.authority_evidence_bundle, 'UTF8')::JSONB THEN
        RAISE EXCEPTION 'Admission tuple differs from original Account issuance' USING ERRCODE = '23514';
    END IF;
    projection_value := convert_from(original.account_redemption_projection, 'UTF8')::JSONB;
    IF projection_value IS DISTINCT FROM jsonb_build_object(
        'projectionSchemaId', 'accountStartSessionRedemptionProjection',
        'projectionSchemaVersion', '1',
        'authorizationReferenceFingerprint', authorization_row.authorization_reference_fingerprint,
        'authorityEvidenceBundle', convert_from(authorization_row.authority_evidence_bundle, 'UTF8')::JSONB,
        'issuanceOperationId', authorization_row.issuance_operation_id::TEXT,
        'issuanceFence', authorization_row.issuance_fence) THEN
        RAISE EXCEPTION 'Admission projection differs from complete original Account redemption' USING ERRCODE = '23514';
    END IF;
    request_value := convert_from(original.request_binding_bytes, 'UTF8')::JSONB;
    IF jsonb_typeof(request_value) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Admission request binding is not an object' USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(request_value)) <> 10
        OR EXISTS (SELECT 1 FROM jsonb_each(request_value) field WHERE jsonb_typeof(field.value) <> 'string')
        OR request_value->>'schema' IS DISTINCT FROM 'account-start-session-admission-protection-request/v1'
        OR request_value->>'originalPostAuthorizationTupleBytesBase64' IS DISTINCT FROM regexp_replace(encode(original.original_post_authorization_tuple, 'base64'), E'[\n\r]', '', 'g')
        OR request_value->>'accountRedemptionProjectionBytesBase64' IS DISTINCT FROM regexp_replace(encode(original.account_redemption_projection, 'base64'), E'[\n\r]', '', 'g')
        OR request_value->>'gameSessionOwnerMutationId' IS DISTINCT FROM original.game_session_owner_mutation_id::TEXT
        OR request_value->>'gameSessionOwnerAttemptId' IS DISTINCT FROM original.game_session_owner_attempt_id::TEXT
        OR request_value->>'gameSessionOwnerFence' IS DISTINCT FROM original.game_session_owner_fence::TEXT
        OR request_value->>'originalLeaseExpiresAt' IS DISTINCT FROM
            to_char(original.original_lease_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"')
        OR request_value->>'accountWorldParticipationId' IS DISTINCT FROM original.account_world_participation_id::TEXT
        OR request_value->>'accountWorldParticipationFence' IS DISTINCT FROM original.account_world_participation_fence::TEXT
        OR request_value->>'worldAdmissionHoldIdentityBytesBase64' IS DISTINCT FROM regexp_replace(encode(original.world_admission_hold_identity_bytes, 'base64'), E'[\n\r]', '', 'g') THEN
        RAISE EXCEPTION 'Admission request binding differs from exact immutable selectors' USING ERRCODE = '23514';
    END IF;
    hold_value := convert_from(original.world_admission_hold_identity_bytes, 'UTF8')::JSONB;
    hold_request := convert_from(decode(hold_value->>'requestBytesBase64', 'base64'), 'UTF8')::JSONB;
    IF hold_value->>'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-identity/v1'
        OR (SELECT count(*) FROM jsonb_object_keys(hold_value)) <> 5
        OR NOT (hold_value ?& ARRAY['schema', 'holdId', 'holdFence', 'holdBindingDigest', 'requestBytesBase64'])
        OR EXISTS (SELECT 1 FROM jsonb_each(hold_value) field WHERE jsonb_typeof(field.value) <> 'string')
        OR (hold_value->>'holdId')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
        OR (hold_value->>'holdFence')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
        OR hold_value->>'holdBindingDigest' IS DISTINCT FROM encode(sha256(decode(hold_value->>'requestBytesBase64', 'base64')), 'hex')
        OR hold_request->>'schema' IS DISTINCT FROM 'world-canonical-initial-admission-hold-request/v1'
        OR hold_request->'request'->>'targetNamespace' IS DISTINCT FROM original.target_namespace
        OR hold_request->'request'->>'canonicalTenantId' IS DISTINCT FROM original.canonical_tenant_id::TEXT
        OR hold_request->'request'->>'canonicalGameInstanceId' IS DISTINCT FROM original.canonical_game_instance_id::TEXT THEN
        RAISE EXCEPTION 'Admission hold identity differs from original instance and request' USING ERRCODE = '23514';
    END IF;
    -- Original World source children remain immutable even when World has settled.
    PERFORM account_start_session_world_participation_assert_sources(world_parent.participation_id, snapshot_value);
    IF require_complete THEN
        IF (SELECT count(*) FROM account_start_session_admission_protection_sources
            WHERE protection_id = original.protection_id) <> jsonb_array_length(snapshot_value->'sourceVector') THEN
            RAISE EXCEPTION 'Admission protection has incomplete or extra source children' USING ERRCODE = '23514';
        END IF;
        FOR source_value IN SELECT value FROM jsonb_array_elements(snapshot_value->'sourceVector') LOOP
            source_bytes := decode(source_value #>> '{}', 'base64');
            source_key_value := account_start_session_world_participation_source_key(source_bytes);
            IF NOT EXISTS (SELECT 1 FROM account_start_session_admission_protection_sources
                WHERE protection_id = original.protection_id AND source_key = source_key_value
                    AND source_evidence = source_bytes) THEN
                RAISE EXCEPTION 'Admission source child differs from complete original capture' USING ERRCODE = '23514';
            END IF;
        END LOOP;
    END IF;
    IF original.original_lease_expires_at <= clock_timestamp()
        OR authorization_row.reference_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'Original StartSession admission expiry elapsed during integrity validation' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION account_ss_admission_read_current_exact(protection_value UUID, fence_value BIGINT, request_value BYTEA)
RETURNS SETOF account_start_session_admission_protections LANGUAGE plpgsql VOLATILE STRICT AS $$
DECLARE original account_start_session_admission_protections%ROWTYPE;
BEGIN
    SELECT * INTO STRICT original FROM account_start_session_admission_protections
        WHERE protection_id = protection_value AND protection_fence = fence_value;
    IF original.request_binding_bytes IS DISTINCT FROM request_value THEN
        RAISE EXCEPTION 'Original admission recovery binding differs' USING ERRCODE = '23514';
    END IF;
    PERFORM account_ss_admission_assert_current(original, TRUE);
    RETURN NEXT original;
END;
$$;

CREATE FUNCTION account_ss_admission_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.producer_xid IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'Admission protection must be created by its producer transaction' USING ERRCODE = '23514';
    END IF;
    PERFORM account_ss_admission_assert_current(NEW::account_start_session_admission_protections, FALSE);
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_ss_admission_source_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE producer BIGINT;
BEGIN
    SELECT producer_xid INTO STRICT producer FROM account_start_session_admission_protections
        WHERE protection_id = NEW.protection_id;
    IF producer IS DISTINCT FROM txid_current()
        OR account_start_session_world_participation_source_key(NEW.source_evidence) IS DISTINCT FROM NEW.source_key THEN
        RAISE EXCEPTION 'Admission sources must be exact same-producer children' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_ss_admission_complete_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original account_start_session_admission_protections%ROWTYPE;
BEGIN
    SELECT * INTO STRICT original FROM account_start_session_admission_protections
        WHERE protection_id = NEW.protection_id;
    PERFORM account_ss_admission_assert_current(original, TRUE);
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_ss_admission_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original StartSession admission evidence is append-only' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_ss_admission_validate BEFORE INSERT ON account_start_session_admission_protections
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_insert_guard();
CREATE TRIGGER account_ss_admission_immutable BEFORE UPDATE OR DELETE ON account_start_session_admission_protections
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE TRIGGER account_ss_admission_no_truncate BEFORE TRUNCATE ON account_start_session_admission_protections
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE TRIGGER account_ss_admission_sources_validate BEFORE INSERT ON account_start_session_admission_protection_sources
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_source_insert_guard();
CREATE TRIGGER account_ss_admission_sources_immutable BEFORE UPDATE OR DELETE ON account_start_session_admission_protection_sources
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE TRIGGER account_ss_admission_sources_no_truncate BEFORE TRUNCATE ON account_start_session_admission_protection_sources
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE CONSTRAINT TRIGGER account_ss_admission_complete AFTER INSERT ON account_start_session_admission_protections
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION account_ss_admission_complete_guard();
CREATE TRIGGER account_ss_admission_settlements_immutable BEFORE UPDATE OR DELETE ON account_start_session_admission_protection_settlements
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_no_mutation();
-- The authenticated exact Game Session terminal API is not yet composed. Reject every
-- settlement insertion until its sealed complete terminal binding guard replaces this
-- unpublished gate. An outcome assertion alone can never release source protection.
CREATE TRIGGER account_ss_admission_settlements_disabled BEFORE INSERT ON account_start_session_admission_protection_settlements
    FOR EACH ROW EXECUTE FUNCTION account_ss_admission_no_mutation();
CREATE TRIGGER account_ss_admission_settlements_no_truncate BEFORE TRUNCATE ON account_start_session_admission_protection_settlements
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_admission_no_mutation();

DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_draft_authorization_sources s$anchor$;
BEGIN
    SELECT pg_get_functiondef('account_control_ui_hold_required_sources(TEXT[])'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one assembled admission source-writer anchor';
    END IF;
    EXECUTE replace(original, anchor,
        E'    PERFORM account_ss_admission_assert_no_pending(source_keys);\n' || anchor);
END;
$migration$;

DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$    FOR operation IN SELECT DISTINCT source.operation_id
        FROM account_draft_authorization_changed_scopes changed$anchor$;
BEGIN
    SELECT pg_get_functiondef('account_draft_authorization_source_change_guard()'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one assembled admission source-change anchor';
    END IF;
    EXECUTE replace(original, anchor, $guard$    PERFORM account_ss_admission_assert_no_pending(
        ARRAY(SELECT source_key FROM account_draft_authorization_changed_scopes
            WHERE change_id = OLD.change_id));
$guard$ || anchor);
END;
$migration$;

DO $migration$
DECLARE original TEXT;
    anchor TEXT := $anchor$    IF OLD.status = 'PREPARED' AND NEW.status = 'DISPATCH_AUTHORIZED'$anchor$;
BEGIN
    SELECT pg_get_functiondef('account_hosted_terms_disclosure_handoff_guard()'::REGPROCEDURE) INTO STRICT original;
    IF (length(original) - length(replace(original, anchor, ''))) / length(anchor) <> 1 THEN
        RAISE EXCEPTION 'Expected exactly one assembled admission disclosure anchor';
    END IF;
    EXECUTE replace(original, anchor, $guard$    IF OLD.status IN ('PREPARED', 'AMBIGUOUS') AND NEW.status = 'DISPATCH_AUTHORIZED' THEN
        PERFORM source_lock.source_key FROM account_draft_authorization_source_locks source_lock
            JOIN (SELECT OLD.source_key AS source_key UNION
                SELECT source_key FROM account_hosted_terms_disclosure_sources
                    WHERE handoff_id = OLD.handoff_id) exact_source USING (source_key)
            ORDER BY account_publication_authorization_source_sort_key(source_lock.source_key)
            FOR UPDATE OF source_lock NOWAIT;
        PERFORM account_ss_admission_assert_no_pending(ARRAY(
            SELECT OLD.source_key UNION SELECT source_key FROM account_hosted_terms_disclosure_sources
                WHERE handoff_id = OLD.handoff_id));
    END IF;
$guard$ || anchor);
END;
$migration$;
-- [jooq ignore end]
