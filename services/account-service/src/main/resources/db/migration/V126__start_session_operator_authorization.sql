CREATE TABLE account_start_session_operator_authorizations (
    control_plane_request_id VARCHAR(128) PRIMARY KEY
        CHECK (octet_length(control_plane_request_id) BETWEEN 1 AND 128),
    pre_authorization_tuple BYTEA NOT NULL
        CHECK (octet_length(pre_authorization_tuple) BETWEEN 1 AND 8192),
    mutation_digest VARCHAR(64) NOT NULL
        CHECK (mutation_digest ~ '^[0-9a-f]{64}$'),
    issuance_workload_uri VARCHAR(256) NOT NULL
        CHECK (issuance_workload_uri ~ '^spiffe://firemud/ns/([a-z0-9]|[a-z0-9][a-z0-9-]{0,61}[a-z0-9])/sa/logging-admin-service$'),
    reservation_owner_id UUID NOT NULL,
    reservation_claim_fence BIGINT NOT NULL CHECK (reservation_claim_fence > 0),
    issuance_operation_id UUID NOT NULL UNIQUE,
    issuance_fence BIGINT NOT NULL CHECK (issuance_fence > 0),
    bundle_version VARCHAR(32) NOT NULL
        CHECK (bundle_version = 'authorityEvidenceBundle/v1'),
    bundle_source_version VARCHAR(19) NOT NULL
        CHECK (bundle_source_version ~ '^[1-9][0-9]{0,18}$'
            AND bundle_source_version::NUMERIC <= 9223372036854775807),
    bundle_source_fence VARCHAR(19) NOT NULL
        CHECK (bundle_source_fence ~ '^[1-9][0-9]{0,18}$'
            AND bundle_source_fence = issuance_fence::TEXT),
    bundle_linearization VARCHAR(20) NOT NULL
        CHECK (bundle_linearization ~ '^[1-9][0-9]{0,19}$'
            AND bundle_linearization::NUMERIC <= 18446744073709551615),
    authority_evidence_bundle BYTEA NOT NULL
        CHECK (octet_length(authority_evidence_bundle) BETWEEN 1 AND 131072),
    authorization_reference_fingerprint VARCHAR(137) NOT NULL UNIQUE
        CHECK (authorization_reference_fingerprint ~ '^arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}$'),
    encrypted_response_envelope BYTEA NOT NULL
        CHECK (octet_length(encrypted_response_envelope) BETWEEN 1 AND 65536),
    issued_at TIMESTAMPTZ NOT NULL,
    reference_expires_at TIMESTAMPTZ NOT NULL,
    response_envelope_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ISSUED', 'REDEEMED')),
    redemption_redeemer_workload_uri VARCHAR(256)
        CHECK (redemption_redeemer_workload_uri IS NULL OR
            redemption_redeemer_workload_uri ~ '^spiffe://firemud/ns/([a-z0-9]|[a-z0-9][a-z0-9-]{0,61}[a-z0-9])/sa/game-session-service$'),
    redemption_owner_attempt_id UUID,
    redemption_owner_fence BIGINT CHECK (redemption_owner_fence IS NULL OR redemption_owner_fence > 0),
    redeemed_at TIMESTAMPTZ,
    redemption_reference_fingerprint VARCHAR(137)
        CHECK (redemption_reference_fingerprint IS NULL OR
            redemption_reference_fingerprint ~ '^arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}$'),
    redemption_authority_evidence_bundle BYTEA
        CHECK (redemption_authority_evidence_bundle IS NULL OR
            octet_length(redemption_authority_evidence_bundle) BETWEEN 1 AND 131072),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (reservation_owner_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (issuance_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (issued_at > CAST('1970-01-01 00:00:00+00:00' AS TIMESTAMPTZ)),
    CHECK (reference_expires_at > issued_at
        AND reference_expires_at <= issued_at + INTERVAL '5' MINUTE),
    CHECK (response_envelope_expires_at > reference_expires_at
        AND response_envelope_expires_at <= reference_expires_at + INTERVAL '1' MINUTE),
    CHECK (
        (status = 'ISSUED'
            AND redemption_redeemer_workload_uri IS NULL
            AND redemption_owner_attempt_id IS NULL
            AND redemption_owner_fence IS NULL
            AND redeemed_at IS NULL
            AND redemption_reference_fingerprint IS NULL
            AND redemption_authority_evidence_bundle IS NULL)
        OR
        (status = 'REDEEMED'
            AND redemption_redeemer_workload_uri IS NOT NULL
            AND redemption_owner_attempt_id IS NOT NULL
            AND redemption_owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND redemption_owner_fence IS NOT NULL
            AND redeemed_at IS NOT NULL
            AND redeemed_at > issued_at
            AND redeemed_at < reference_expires_at
            AND redemption_reference_fingerprint = authorization_reference_fingerprint
            AND redemption_authority_evidence_bundle = authority_evidence_bundle)
    )
);

-- [jooq ignore start]
CREATE FUNCTION account_start_session_operator_authorization_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ISSUED'
            OR NEW.redemption_redeemer_workload_uri IS NOT NULL
            OR NEW.redemption_owner_attempt_id IS NOT NULL
            OR NEW.redemption_owner_fence IS NOT NULL
            OR NEW.redeemed_at IS NOT NULL
            OR NEW.redemption_reference_fingerprint IS NOT NULL
            OR NEW.redemption_authority_evidence_bundle IS NOT NULL THEN
            RAISE EXCEPTION 'StartSession authorization rows must begin unredeemed'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'StartSession operator authorization evidence cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    IF (to_jsonb(NEW) - ARRAY['status','redemption_redeemer_workload_uri',
            'redemption_owner_attempt_id','redemption_owner_fence','redeemed_at',
            'redemption_reference_fingerprint','redemption_authority_evidence_bundle']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','redemption_redeemer_workload_uri',
            'redemption_owner_attempt_id','redemption_owner_fence','redeemed_at',
            'redemption_reference_fingerprint','redemption_authority_evidence_bundle'])
        OR OLD.status <> 'ISSUED'
        OR NEW.status <> 'REDEEMED'
        OR OLD.redemption_redeemer_workload_uri IS NOT NULL
        OR OLD.redemption_owner_attempt_id IS NOT NULL
        OR OLD.redemption_owner_fence IS NOT NULL
        OR OLD.redeemed_at IS NOT NULL
        OR OLD.redemption_reference_fingerprint IS NOT NULL
        OR OLD.redemption_authority_evidence_bundle IS NOT NULL
        OR NEW.redemption_redeemer_workload_uri IS NULL
        OR NEW.redemption_owner_attempt_id IS NULL
        OR NEW.redemption_owner_fence IS NULL
        OR NEW.redeemed_at IS NULL
        OR NEW.redemption_reference_fingerprint IS DISTINCT FROM NEW.authorization_reference_fingerprint
        OR NEW.redemption_authority_evidence_bundle IS DISTINCT FROM NEW.authority_evidence_bundle
        OR NEW.redemption_redeemer_workload_uri IS DISTINCT FROM
            regexp_replace(OLD.issuance_workload_uri, '/sa/logging-admin-service$', '/sa/game-session-service') THEN
        RAISE EXCEPTION 'StartSession authorization evidence is immutable except one exact redemption'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_start_session_operator_authorization_no_truncate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'StartSession operator authorization evidence cannot be truncated'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_start_session_operator_authorization_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON account_start_session_operator_authorizations
    FOR EACH ROW EXECUTE FUNCTION account_start_session_operator_authorization_guard();
CREATE TRIGGER account_start_session_operator_authorization_no_truncate
    BEFORE TRUNCATE ON account_start_session_operator_authorizations
    FOR EACH STATEMENT EXECUTE FUNCTION account_start_session_operator_authorization_no_truncate();
-- [jooq ignore stop]
