-- Retain the exact first Game Design selection and descriptor pin for one original StartSession.
-- This is immutable historical evidence; it creates no claim, launch, or runtime authority.
CREATE TABLE game_session_original_start_session_selection_pin (
    target_namespace VARCHAR(63) NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    game_instance_uuid UUID NOT NULL,
    launch_descriptor_id VARCHAR(128) NOT NULL,
    owner_attempt_id UUID NOT NULL,
    owner_mutation_id UUID NOT NULL,
    owner_fence BIGINT NOT NULL,
    owner_lease_expires_at TIMESTAMPTZ NOT NULL,
    original_post_authorization_tuple BYTEA NOT NULL,
    account_redemption_projection BYTEA NOT NULL,
    initial_selection_request_bytes BYTEA NOT NULL,
    initial_selection_request_digest VARCHAR(71) NOT NULL,
    initial_selection_response_bytes BYTEA NOT NULL,
    initial_selection_response_digest VARCHAR(71) NOT NULL,
    descriptor_pin_request_bytes BYTEA NOT NULL,
    descriptor_pin_request_digest VARCHAR(71) NOT NULL,
    descriptor_pin_response_bytes BYTEA NOT NULL,
    descriptor_pin_response_digest VARCHAR(71) NOT NULL,
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_gs_original_start_session_selection_pin
        PRIMARY KEY (target_namespace, control_plane_request_id),
    CONSTRAINT uq_gs_original_start_session_selection_pin_attempt UNIQUE (owner_attempt_id),
    CONSTRAINT fk_gs_original_start_session_selection_pin_launch
        FOREIGN KEY (target_namespace, control_plane_request_id)
        REFERENCES game_session_canonical_instance_launch
            (target_namespace, control_plane_request_id) ON DELETE RESTRICT,
    CONSTRAINT ck_gs_original_start_session_selection_pin_identity CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND control_plane_request_id !~ '^[[:space:]]*$'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND game_instance_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
        AND launch_descriptor_id !~ '^[[:space:]]*$'
        AND owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_fence > 0),
    CONSTRAINT ck_gs_original_start_session_selection_pin_bytes CHECK (
        octet_length(original_post_authorization_tuple) BETWEEN 1 AND 262144
        AND octet_length(account_redemption_projection) BETWEEN 1 AND 262144
        AND octet_length(initial_selection_request_bytes) BETWEEN 1 AND 524288
        AND octet_length(initial_selection_response_bytes) BETWEEN 1 AND 8388608
        AND octet_length(descriptor_pin_request_bytes) BETWEEN 1 AND 524288
        AND octet_length(descriptor_pin_response_bytes) BETWEEN 1 AND 8388608
        AND initial_selection_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND initial_selection_request_digest =
            'sha256:' || encode(sha256(initial_selection_request_bytes), 'hex')
        AND initial_selection_response_digest ~ '^sha256:[0-9a-f]{64}$'
        AND initial_selection_response_digest =
            'sha256:' || encode(sha256(initial_selection_response_bytes), 'hex')
        AND descriptor_pin_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_pin_request_digest =
            'sha256:' || encode(sha256(descriptor_pin_request_bytes), 'hex')
        AND descriptor_pin_response_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_pin_response_digest =
            'sha256:' || encode(sha256(descriptor_pin_response_bytes), 'hex'))
);

-- V35 is hidden from jOOQ's DDL simulator; preserve the PostgreSQL relation while excluding
-- only this cross-relation constraint from simulation, as in V45.
-- [jooq ignore start]
ALTER TABLE game_session_original_start_session_selection_pin
    ADD CONSTRAINT fk_gs_original_start_session_selection_pin_attempt
        FOREIGN KEY (target_namespace, control_plane_request_id)
        REFERENCES game_session_start_session_operator_attempt
            (target_namespace, control_plane_request_id) ON DELETE RESTRICT;
-- [jooq ignore stop]

-- [jooq ignore start]
CREATE FUNCTION guard_gs_original_start_session_selection_pin()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE launch game_session_canonical_instance_launch%ROWTYPE;
    attempt game_session_start_session_operator_attempt%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Game Session original selection pin is immutable and append-only'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO STRICT launch
      FROM game_session_canonical_instance_launch
     WHERE target_namespace = NEW.target_namespace
       AND control_plane_request_id = NEW.control_plane_request_id;
    SELECT * INTO STRICT attempt
      FROM game_session_start_session_operator_attempt
     WHERE target_namespace = NEW.target_namespace
       AND control_plane_request_id = NEW.control_plane_request_id;

    IF launch.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR launch.game_instance_uuid IS DISTINCT FROM NEW.game_instance_uuid
        OR launch.original_start_session_tuple IS DISTINCT FROM
            NEW.original_post_authorization_tuple
        OR launch.launch_descriptor_id IS DISTINCT FROM NEW.launch_descriptor_id
        OR attempt.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR attempt.owner_attempt_id IS DISTINCT FROM NEW.owner_attempt_id
        OR attempt.owner_mutation_id IS DISTINCT FROM NEW.owner_mutation_id
        OR attempt.owner_fence IS DISTINCT FROM NEW.owner_fence
        OR attempt.post_authorization_execution_tuple IS DISTINCT FROM
            NEW.original_post_authorization_tuple
        OR attempt.account_redemption_projection IS DISTINCT FROM
            NEW.account_redemption_projection
        OR attempt.phase_state IS DISTINCT FROM 'OWNER_EXECUTION_PENDING'
        OR attempt.lease_expires_at IS DISTINCT FROM NEW.owner_lease_expires_at
        OR attempt.lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'Selection pin differs from exact live launch or original owner attempt'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'ck_gs_original_start_session_selection_pin_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_original_start_session_selection_pin_guard
    BEFORE INSERT OR UPDATE OR DELETE
    ON game_session_original_start_session_selection_pin
    FOR EACH ROW EXECUTE FUNCTION guard_gs_original_start_session_selection_pin();

CREATE FUNCTION reject_gs_original_start_session_selection_pin_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Game Session original selection pin cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$;

CREATE TRIGGER game_session_original_start_session_selection_pin_no_truncate
    BEFORE TRUNCATE ON game_session_original_start_session_selection_pin
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gs_original_start_session_selection_pin_truncate();
-- [jooq ignore stop]
