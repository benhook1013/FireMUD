-- Inactive Game Session cleanup journal. Account remains the sole issuance/disposal authority.
-- No credentials, JWTs, Account owner digests, installation authority or expiry are stored.
CREATE TABLE game_session_initial_gameplay_login_journal (
    request_id UUID PRIMARY KEY,
    caller_context_id UUID NOT NULL,
    expected_attempt_fence BIGINT NOT NULL CHECK (expected_attempt_fence > 0),
    abandoned BOOLEAN NOT NULL DEFAULT FALSE,
    verified_token_jti UUID,
    verified_token_sha256 VARCHAR(64),
    verified_token_generation BIGINT,
    verified_issuance_fence BIGINT,
    retirement_request_bytes BYTEA,
    disposal_operation_id UUID,
    disposal_disposition VARCHAR(8),
    disposal_proof_kind VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    abandoned_at TIMESTAMP WITH TIME ZONE,
    UNIQUE (caller_context_id, expected_attempt_fence),
    CONSTRAINT chk_gs_login_original_v4 CHECK (
        request_id::TEXT ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND caller_context_id::TEXT ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT chk_gs_login_witness_complete CHECK (((
        verified_token_jti IS NULL AND verified_token_sha256 IS NULL
        AND verified_token_generation IS NULL AND verified_issuance_fence IS NULL)
        OR (verified_token_jti IS NOT NULL
            AND verified_token_jti::TEXT ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND verified_token_sha256 IS NOT NULL AND verified_token_sha256 ~ '^[0-9a-f]{64}$'
            AND verified_token_generation IS NOT NULL AND verified_token_generation = 1
            AND verified_issuance_fence IS NOT NULL AND verified_issuance_fence > 0)) IS TRUE),
    CONSTRAINT chk_gs_login_abandonment CHECK (
        (abandoned AND abandoned_at IS NOT NULL)
        OR (NOT abandoned AND abandoned_at IS NULL)),
    CONSTRAINT chk_gs_login_frozen_request CHECK (
        retirement_request_bytes IS NULL
        OR (abandoned AND octet_length(retirement_request_bytes) BETWEEN 1 AND 512)),
    CONSTRAINT chk_gs_login_disposal_receipt CHECK (((
        disposal_operation_id IS NULL AND disposal_disposition IS NULL AND disposal_proof_kind IS NULL)
        OR (abandoned AND retirement_request_bytes IS NOT NULL
            AND disposal_operation_id IS NOT NULL
            AND disposal_operation_id::TEXT ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND disposal_disposition IS NOT NULL AND disposal_proof_kind IS NOT NULL
            AND ((disposal_disposition = 'PENDING' AND disposal_proof_kind = 'NONE')
                OR (disposal_disposition = 'RETIRED' AND disposal_proof_kind = 'PHYSICAL_TOKEN_DENIAL')
                OR (disposal_disposition = 'NO_TOKEN' AND disposal_proof_kind = 'NO_ISSUANCE_BINDING')))) IS TRUE)
);

-- Retention release and restore qualification are deliberately absent. Preserve all rows,
-- original identities, irreversible abandonment, late witness and frozen request/owner receipt.
-- [jooq ignore start]
CREATE FUNCTION guard_initial_gameplay_login_journal() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Initial gameplay LOGIN journal retention is held' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.abandoned OR NEW.abandoned_at IS NOT NULL
           OR NEW.verified_token_jti IS NOT NULL OR NEW.verified_token_sha256 IS NOT NULL
           OR NEW.verified_token_generation IS NOT NULL OR NEW.verified_issuance_fence IS NOT NULL
           OR NEW.retirement_request_bytes IS NOT NULL OR NEW.disposal_operation_id IS NOT NULL
           OR NEW.disposal_disposition IS NOT NULL OR NEW.disposal_proof_kind IS NOT NULL THEN
            RAISE EXCEPTION 'Initial gameplay LOGIN journal must begin as an original reservation'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.request_id, NEW.caller_context_id, NEW.expected_attempt_fence, NEW.created_at)
           IS DISTINCT FROM ROW(OLD.request_id, OLD.caller_context_id, OLD.expected_attempt_fence, OLD.created_at)
       OR (OLD.abandoned AND (NOT NEW.abandoned OR NEW.abandoned_at IS DISTINCT FROM OLD.abandoned_at))
       OR (OLD.verified_token_jti IS NOT NULL
           AND ROW(NEW.verified_token_jti, NEW.verified_token_sha256,
                   NEW.verified_token_generation, NEW.verified_issuance_fence)
               IS DISTINCT FROM ROW(OLD.verified_token_jti, OLD.verified_token_sha256,
                                    OLD.verified_token_generation, OLD.verified_issuance_fence))
       OR (OLD.retirement_request_bytes IS NOT NULL
           AND NEW.retirement_request_bytes IS DISTINCT FROM OLD.retirement_request_bytes)
       OR (OLD.disposal_operation_id IS NOT NULL
           AND NEW.disposal_operation_id IS DISTINCT FROM OLD.disposal_operation_id)
       OR (OLD.disposal_disposition IN ('RETIRED', 'NO_TOKEN')
           AND ROW(NEW.disposal_disposition, NEW.disposal_proof_kind)
               IS DISTINCT FROM ROW(OLD.disposal_disposition, OLD.disposal_proof_kind)) THEN
        RAISE EXCEPTION 'Initial gameplay LOGIN retained evidence cannot be replaced or reopened'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gs_login_journal_retained_evidence
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_initial_gameplay_login_journal
    FOR EACH ROW EXECUTE FUNCTION guard_initial_gameplay_login_journal();
CREATE TRIGGER gs_login_journal_retention_truncate
    BEFORE TRUNCATE ON game_session_initial_gameplay_login_journal
    FOR EACH STATEMENT EXECUTE FUNCTION guard_initial_gameplay_login_journal();
-- [jooq ignore stop]
