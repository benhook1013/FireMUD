ALTER TABLE account_start_session_operator_authorizations
    ALTER COLUMN encrypted_response_envelope DROP NOT NULL;

ALTER TABLE account_start_session_operator_authorizations
    ADD COLUMN response_envelope_erased_at TIMESTAMPTZ;

ALTER TABLE account_start_session_operator_authorizations
    ADD CONSTRAINT account_start_session_operator_authorizations_envelope_erasure_state
    CHECK (
        (encrypted_response_envelope IS NOT NULL AND response_envelope_erased_at IS NULL)
        OR
        (encrypted_response_envelope IS NULL
            AND response_envelope_erased_at IS NOT NULL
            AND response_envelope_erased_at >= response_envelope_expires_at)
    );

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_start_session_operator_authorization_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ISSUED'
            OR NEW.redemption_redeemer_workload_uri IS NOT NULL
            OR NEW.redemption_owner_attempt_id IS NOT NULL
            OR NEW.redemption_owner_fence IS NOT NULL
            OR NEW.redeemed_at IS NOT NULL
            OR NEW.redemption_reference_fingerprint IS NOT NULL
            OR NEW.redemption_authority_evidence_bundle IS NOT NULL
            OR NEW.encrypted_response_envelope IS NULL
            OR NEW.response_envelope_erased_at IS NOT NULL THEN
            RAISE EXCEPTION 'StartSession authorization rows must begin unredeemed and unerased'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'StartSession operator authorization evidence cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.encrypted_response_envelope IS NOT NULL
        AND OLD.response_envelope_erased_at IS NULL
        AND NEW.encrypted_response_envelope IS NULL
        AND NEW.response_envelope_erased_at IS NULL THEN
        IF OLD.response_envelope_expires_at > clock_timestamp()
            OR (to_jsonb(NEW) - ARRAY['encrypted_response_envelope','response_envelope_erased_at'])
                IS DISTINCT FROM
               (to_jsonb(OLD) - ARRAY['encrypted_response_envelope','response_envelope_erased_at']) THEN
            RAISE EXCEPTION 'StartSession response envelope cannot be erased before expiry or with other changes'
                USING ERRCODE = '23514';
        END IF;
        NEW.response_envelope_erased_at := clock_timestamp();
        RETURN NEW;
    END IF;

    IF (to_jsonb(NEW) - ARRAY['status','redemption_redeemer_workload_uri',
            'redemption_owner_attempt_id','redemption_owner_fence','redeemed_at',
            'redemption_reference_fingerprint','redemption_authority_evidence_bundle']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','redemption_redeemer_workload_uri',
            'redemption_owner_attempt_id','redemption_owner_fence','redeemed_at',
            'redemption_reference_fingerprint','redemption_authority_evidence_bundle'])
        OR OLD.status <> 'ISSUED'
        OR NEW.status <> 'REDEEMED'
        OR OLD.encrypted_response_envelope IS NULL
        OR OLD.response_envelope_erased_at IS NOT NULL
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
        RAISE EXCEPTION 'StartSession authorization evidence is immutable except one exact redemption or expired envelope erasure'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
