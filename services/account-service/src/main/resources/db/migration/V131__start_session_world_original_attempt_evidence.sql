-- Retain an observation of the exact live original Game Session attempt beside the
-- distinct Account-owned World participation. This is not producer authentication,
-- continuous owner authority, or admission permission.
CREATE TABLE account_start_session_world_original_attempt_evidence (
    participation_id UUID PRIMARY KEY
        REFERENCES account_start_session_world_participations(participation_id) ON DELETE RESTRICT,
    target_namespace VARCHAR(63) NOT NULL
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    game_session_owner_attempt_id UUID NOT NULL
        CHECK (game_session_owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_mutation_id UUID NOT NULL UNIQUE
        CHECK (game_session_owner_mutation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    game_session_owner_fence BIGINT NOT NULL CHECK (game_session_owner_fence > 0),
    original_lease_expires_at TIMESTAMPTZ NOT NULL
        CHECK (isfinite(original_lease_expires_at)),
    original_response_bytes BYTEA NOT NULL
        CHECK (octet_length(original_response_bytes) BETWEEN 1 AND 525824),
    original_response_digest VARCHAR(71) NOT NULL
        CHECK (original_response_digest ~ '^sha256:[0-9a-f]{64}$')
        CHECK (original_response_digest = 'sha256:' || encode(sha256(original_response_bytes), 'hex'))
);

-- [jooq ignore start]
CREATE FUNCTION account_ss_world_attempt_evidence_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    parent account_start_session_world_participations%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Original StartSession attempt observation is append-only'
            USING ERRCODE = '23514';
    END IF;
    -- Lock first so a concurrent terminal settlement cannot pass the pending-parent check.
    SELECT * INTO STRICT parent FROM account_start_session_world_participations
        WHERE participation_id = NEW.participation_id FOR UPDATE;
    IF parent.target_namespace IS DISTINCT FROM NEW.target_namespace
        OR parent.game_session_owner_attempt_id IS DISTINCT FROM NEW.game_session_owner_attempt_id
        OR parent.game_session_owner_fence IS DISTINCT FROM NEW.game_session_owner_fence THEN
        RAISE EXCEPTION 'Original StartSession attempt observation differs from its participation'
            USING ERRCODE = '23514';
    END IF;
    IF account_start_session_world_participation_is_settled(NEW.participation_id) THEN
        RAISE EXCEPTION 'Original StartSession attempt observation requires pending participation'
            USING ERRCODE = '23514';
    END IF;
    -- This is a database-clock freshness guard after any parent lock wait. It does not
    -- authenticate Game Session or assert that this point-in-time observation stays current.
    IF NEW.original_lease_expires_at <= clock_timestamp() THEN
        RAISE EXCEPTION 'Original StartSession attempt observation lease is expired'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_ss_world_attempt_evidence_no_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Original StartSession attempt observation is append-only'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_ss_world_attempt_evidence_validate
    BEFORE INSERT ON account_start_session_world_original_attempt_evidence
    FOR EACH ROW EXECUTE FUNCTION account_ss_world_attempt_evidence_insert_guard();
CREATE TRIGGER account_ss_world_attempt_evidence_immutable
    BEFORE UPDATE OR DELETE ON account_start_session_world_original_attempt_evidence
    FOR EACH ROW EXECUTE FUNCTION account_ss_world_attempt_evidence_no_mutation();
CREATE TRIGGER account_ss_world_attempt_evidence_no_truncate
    BEFORE TRUNCATE ON account_start_session_world_original_attempt_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION account_ss_world_attempt_evidence_no_mutation();
-- [jooq ignore end]
