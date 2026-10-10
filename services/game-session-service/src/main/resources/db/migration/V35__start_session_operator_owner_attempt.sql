-- Game Session's non-replayable owner-side reservation for a complete authorized StartSession.
-- This table is intentionally not a runtime-activation or business-mutation switch.
-- [jooq ignore start]
CREATE SEQUENCE game_session_start_session_operator_owner_fence_seq
    AS BIGINT START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE NO CYCLE;

CREATE TABLE game_session_start_session_operator_attempt (
    target_namespace VARCHAR(63) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    owner_attempt_id UUID NOT NULL,
    owner_mutation_id UUID NOT NULL,
    claim_owner_id UUID NOT NULL,
    owner_fence BIGINT NOT NULL,
    post_authorization_execution_tuple BYTEA NOT NULL,
    mutation_digest VARCHAR(64) NOT NULL,
    authorization_reference_fingerprint VARCHAR(137) NOT NULL,
    phase_state VARCHAR(48) NOT NULL DEFAULT 'OWNER_EXECUTION_PENDING',
    account_redemption_projection BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_gs_start_session_operator_attempt
        PRIMARY KEY (target_namespace, control_plane_request_id),
    CONSTRAINT uq_gs_start_session_operator_attempt_id UNIQUE (owner_attempt_id),
    CONSTRAINT uq_gs_start_session_operator_mutation_id UNIQUE (owner_mutation_id),
    CONSTRAINT uq_gs_start_session_operator_fence UNIQUE (owner_fence),
    CONSTRAINT chk_gs_start_session_operator_attempt_identity CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND octet_length(control_plane_request_id) BETWEEN 1 AND 128
        AND control_plane_request_id !~ '^[[:space:]]*$'
        AND control_plane_request_id !~ '[[:cntrl:]]'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND claim_owner_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_fence > 0
        AND lease_expires_at > created_at),
    CONSTRAINT chk_gs_start_session_operator_attempt_tuple CHECK (
        octet_length(post_authorization_execution_tuple) BETWEEN 1 AND 262144
        AND mutation_digest ~ '^[0-9a-f]{64}$'
        AND authorization_reference_fingerprint
            ~ '^arfp/v1/[A-Za-z0-9_-]{1,64}/[0-9a-f]{64}$'),
    CONSTRAINT chk_gs_start_session_operator_attempt_pending CHECK (
        phase_state = 'OWNER_EXECUTION_PENDING'
        AND (account_redemption_projection IS NULL
            OR octet_length(account_redemption_projection) BETWEEN 1 AND 262144))
);

CREATE FUNCTION enforce_game_session_start_session_operator_attempt_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Game Session StartSession owner attempts are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF OLD.account_redemption_projection IS NOT NULL
       OR NEW.account_redemption_projection IS NULL
       OR NEW.target_namespace IS DISTINCT FROM OLD.target_namespace
       OR NEW.control_plane_request_id IS DISTINCT FROM OLD.control_plane_request_id
       OR NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
       OR NEW.owner_attempt_id IS DISTINCT FROM OLD.owner_attempt_id
       OR NEW.owner_mutation_id IS DISTINCT FROM OLD.owner_mutation_id
       OR NEW.claim_owner_id IS DISTINCT FROM OLD.claim_owner_id
       OR NEW.owner_fence IS DISTINCT FROM OLD.owner_fence
       OR NEW.post_authorization_execution_tuple IS DISTINCT FROM OLD.post_authorization_execution_tuple
       OR NEW.mutation_digest IS DISTINCT FROM OLD.mutation_digest
       OR NEW.authorization_reference_fingerprint IS DISTINCT FROM OLD.authorization_reference_fingerprint
       OR NEW.phase_state IS DISTINCT FROM OLD.phase_state
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.lease_expires_at IS DISTINCT FROM OLD.lease_expires_at THEN
        RAISE EXCEPTION 'Game Session StartSession owner attempt evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_start_session_operator_attempt_immutable
    BEFORE UPDATE OR DELETE ON game_session_start_session_operator_attempt
    FOR EACH ROW EXECUTE FUNCTION enforce_game_session_start_session_operator_attempt_immutable();

CREATE FUNCTION reject_game_session_start_session_operator_attempt_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Game Session StartSession owner attempts cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER game_session_start_session_operator_attempt_no_truncate
    BEFORE TRUNCATE ON game_session_start_session_operator_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_start_session_operator_attempt_truncate();
-- [jooq ignore stop]
