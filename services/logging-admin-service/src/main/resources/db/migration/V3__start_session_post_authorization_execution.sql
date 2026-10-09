ALTER TABLE start_session_pre_authorization_reservations
    ADD COLUMN post_authorization_execution_tuple_json TEXT,
    ADD COLUMN owner_execution_handoff_id UUID;

ALTER TABLE start_session_pre_authorization_reservations
    DROP CONSTRAINT start_session_pre_authorization_reservations_phase_check;

ALTER TABLE start_session_pre_authorization_reservations
    DROP CONSTRAINT start_session_pre_authorization_reservations_state_check;

ALTER TABLE start_session_pre_authorization_reservations
    ADD CONSTRAINT start_session_pre_authorization_reservations_phase_check
        CHECK (phase IN ('ACCOUNT_AUTHORIZATION', 'OWNER_EXECUTION')),
    ADD CONSTRAINT start_session_pre_authorization_reservations_state_check
        CHECK (
            (phase = 'ACCOUNT_AUTHORIZATION'
                AND state IN ('RESERVED', 'AUTHORIZATION_PENDING', 'AUTHORIZED'))
            OR (phase = 'OWNER_EXECUTION' AND state = 'OWNER_EXECUTION_PENDING')
        ),
    ADD CONSTRAINT start_session_pre_authorization_reservations_reservation_owner_non_nil_check
        CHECK (reservation_owner_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    ADD CONSTRAINT start_session_pre_authorization_reservations_claim_owner_non_nil_check
        CHECK (
            claim_owner_id IS NULL
            OR claim_owner_id <> '00000000-0000-0000-0000-000000000000'::UUID
        ),
    ADD CONSTRAINT start_session_pre_authorization_reservations_handoff_non_nil_check
        CHECK (
            owner_execution_handoff_id IS NULL
            OR owner_execution_handoff_id <> '00000000-0000-0000-0000-000000000000'::UUID
        ),
    ADD CONSTRAINT start_session_pre_authorization_reservations_post_tuple_bound_check
        CHECK (
            post_authorization_execution_tuple_json IS NULL
            OR octet_length(post_authorization_execution_tuple_json) BETWEEN 1 AND 262144
        ),
    ADD CONSTRAINT start_session_pre_authorization_reservations_post_state_shape_check
        CHECK (
            (phase = 'ACCOUNT_AUTHORIZATION'
                AND state IN ('RESERVED', 'AUTHORIZATION_PENDING')
                AND post_authorization_execution_tuple_json IS NULL
                AND owner_execution_handoff_id IS NULL)
            OR (phase = 'ACCOUNT_AUTHORIZATION'
                AND state = 'AUTHORIZED'
                AND post_authorization_execution_tuple_json IS NOT NULL
                AND owner_execution_handoff_id IS NULL)
            OR (phase = 'OWNER_EXECUTION'
                AND state = 'OWNER_EXECUTION_PENDING'
                AND post_authorization_execution_tuple_json IS NOT NULL
                AND owner_execution_handoff_id IS NOT NULL)
        );

-- jOOQ's H2 DDL parser cannot model PostgreSQL trigger functions; PostgreSQL still applies the
-- complete immutable-evidence and phase-transition guards below.
-- [jooq ignore start]
CREATE FUNCTION guard_start_session_reservation_insert()
RETURNS trigger AS $$
BEGIN
    IF NEW.phase <> 'ACCOUNT_AUTHORIZATION'
        OR NEW.state <> 'RESERVED'
        OR NEW.reservation_owner_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.reservation_claim_fence <> 1
        OR NEW.claim_owner_id IS DISTINCT FROM NEW.reservation_owner_id
        OR NEW.claim_fence <> 1
        OR NEW.claim_state <> 'ACTIVE'
        OR NEW.post_authorization_execution_tuple_json IS NOT NULL
        OR NEW.owner_execution_handoff_id IS NOT NULL THEN
        RAISE EXCEPTION 'StartSession reservation must begin in the exact reserved state';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER start_session_reservation_insert_guard
    BEFORE INSERT ON start_session_pre_authorization_reservations
    FOR EACH ROW
    EXECUTE FUNCTION guard_start_session_reservation_insert();

CREATE FUNCTION guard_start_session_post_authorization_execution()
RETURNS trigger AS $$
BEGIN
    IF NEW.control_plane_request_id IS DISTINCT FROM OLD.control_plane_request_id
        OR NEW.pre_authorization_tuple_json IS DISTINCT FROM OLD.pre_authorization_tuple_json
        OR NEW.mutation_digest IS DISTINCT FROM OLD.mutation_digest
        OR NEW.reservation_owner_id IS DISTINCT FROM OLD.reservation_owner_id
        OR NEW.reservation_claim_fence IS DISTINCT FROM OLD.reservation_claim_fence
        OR NEW.created_at_epoch_ms IS DISTINCT FROM OLD.created_at_epoch_ms THEN
        RAISE EXCEPTION 'StartSession reservation identity is immutable';
    END IF;

    IF OLD.post_authorization_execution_tuple_json IS NOT NULL
        AND NEW.post_authorization_execution_tuple_json
            IS DISTINCT FROM OLD.post_authorization_execution_tuple_json THEN
        RAISE EXCEPTION 'StartSession post-authorization tuple is immutable';
    END IF;
    IF OLD.owner_execution_handoff_id IS NOT NULL
        AND NEW.owner_execution_handoff_id IS DISTINCT FROM OLD.owner_execution_handoff_id THEN
        RAISE EXCEPTION 'StartSession owner handoff is immutable';
    END IF;

    IF NOT (
        (NEW.phase = OLD.phase AND NEW.state = OLD.state)
        OR (OLD.phase = 'ACCOUNT_AUTHORIZATION' AND OLD.state = 'RESERVED'
            AND NEW.phase = 'ACCOUNT_AUTHORIZATION' AND NEW.state = 'AUTHORIZATION_PENDING')
        OR (OLD.phase = 'ACCOUNT_AUTHORIZATION' AND OLD.state = 'AUTHORIZATION_PENDING'
            AND NEW.phase = 'ACCOUNT_AUTHORIZATION' AND NEW.state = 'AUTHORIZED')
        OR (OLD.phase = 'ACCOUNT_AUTHORIZATION' AND OLD.state = 'AUTHORIZED'
            AND NEW.phase = 'OWNER_EXECUTION' AND NEW.state = 'OWNER_EXECUTION_PENDING')
    ) THEN
        RAISE EXCEPTION 'StartSession phase-qualified transition is not permitted';
    END IF;

    IF OLD.phase = 'ACCOUNT_AUTHORIZATION' AND OLD.state = 'AUTHORIZATION_PENDING'
        AND NEW.phase = 'ACCOUNT_AUTHORIZATION' AND NEW.state = 'AUTHORIZED' THEN
        IF OLD.post_authorization_execution_tuple_json IS NOT NULL
            OR NEW.post_authorization_execution_tuple_json IS NULL
            OR NEW.owner_execution_handoff_id IS NOT NULL
            OR NEW.claim_owner_id IS DISTINCT FROM OLD.claim_owner_id
            OR NEW.claim_fence IS DISTINCT FROM OLD.claim_fence
            OR NEW.claim_expires_at_epoch_ms IS DISTINCT FROM OLD.claim_expires_at_epoch_ms
            OR NEW.claim_state IS DISTINCT FROM OLD.claim_state THEN
            RAISE EXCEPTION 'StartSession authorization enrichment must preserve the exact claim';
        END IF;
    END IF;

    IF OLD.phase = 'ACCOUNT_AUTHORIZATION' AND OLD.state = 'AUTHORIZED'
        AND NEW.phase = 'OWNER_EXECUTION' AND NEW.state = 'OWNER_EXECUTION_PENDING' THEN
        IF OLD.post_authorization_execution_tuple_json IS NULL
            OR NEW.post_authorization_execution_tuple_json
                IS DISTINCT FROM OLD.post_authorization_execution_tuple_json
            OR NEW.owner_execution_handoff_id IS NULL
            OR OLD.owner_execution_handoff_id IS NOT NULL
            OR NEW.claim_owner_id IS DISTINCT FROM OLD.claim_owner_id
            OR NEW.claim_fence IS DISTINCT FROM OLD.claim_fence
            OR NEW.claim_expires_at_epoch_ms IS DISTINCT FROM OLD.claim_expires_at_epoch_ms
            OR NEW.claim_state IS DISTINCT FROM OLD.claim_state THEN
            RAISE EXCEPTION 'StartSession owner handoff must preserve the exact claim and tuple';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER start_session_post_authorization_execution_guard
    BEFORE UPDATE ON start_session_pre_authorization_reservations
    FOR EACH ROW
    EXECUTE FUNCTION guard_start_session_post_authorization_execution();

CREATE FUNCTION reject_start_session_reservation_delete()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'StartSession reservation evidence cannot be deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER start_session_reservation_no_delete
    BEFORE DELETE ON start_session_pre_authorization_reservations
    FOR EACH ROW
    EXECUTE FUNCTION reject_start_session_reservation_delete();

CREATE FUNCTION reject_start_session_reservation_truncate()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'StartSession reservation evidence cannot be truncated';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER start_session_reservation_no_truncate
    BEFORE TRUNCATE ON start_session_pre_authorization_reservations
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_start_session_reservation_truncate();
-- [jooq ignore stop]
