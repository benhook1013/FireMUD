ALTER TABLE start_session_pre_authorization_reservations
    ADD COLUMN claim_purpose VARCHAR(40);

UPDATE start_session_pre_authorization_reservations
SET claim_purpose = CASE
    WHEN claim_state = 'EXPIRED' THEN NULL
    WHEN claim_owner_id = reservation_owner_id
        AND claim_fence = reservation_claim_fence THEN 'ORIGINAL'
    WHEN state = 'RESERVED' THEN 'RESERVED_RECOVERY_ISSUE'
    ELSE 'AUTHORIZATION_RECOVERY'
END;

ALTER TABLE start_session_pre_authorization_reservations
    ADD CONSTRAINT start_session_pre_authorization_reservations_claim_purpose_shape_check
        CHECK (
            (claim_state = 'EXPIRED'
                AND claim_owner_id IS NULL
                AND claim_purpose IS NULL)
            OR (claim_state = 'ACTIVE'
                AND claim_owner_id IS NOT NULL
                AND claim_purpose IS NOT NULL
                AND claim_purpose IN (
                    'ORIGINAL',
                    'RESERVED_RECOVERY_ISSUE',
                    'AUTHORIZATION_RECOVERY',
                    'AUTHORIZED_RESPONSE_RECOVERY'
                )
                AND (
                    (claim_purpose = 'ORIGINAL'
                        AND claim_owner_id = reservation_owner_id
                        AND claim_fence = reservation_claim_fence)
                    OR (claim_purpose <> 'ORIGINAL'
                        AND claim_owner_id <> reservation_owner_id
                        AND claim_fence > reservation_claim_fence)
                )
                AND (
                    claim_purpose = 'ORIGINAL'
                    OR (claim_purpose = 'RESERVED_RECOVERY_ISSUE'
                        AND state IN (
                            'RESERVED',
                            'AUTHORIZATION_PENDING',
                            'AUTHORIZED',
                            'OWNER_EXECUTION_PENDING'
                        ))
                    OR (claim_purpose = 'AUTHORIZATION_RECOVERY'
                        AND state IN (
                            'AUTHORIZATION_PENDING',
                            'AUTHORIZED',
                            'OWNER_EXECUTION_PENDING'
                        ))
                    OR (claim_purpose = 'AUTHORIZED_RESPONSE_RECOVERY'
                        AND state IN ('AUTHORIZED', 'OWNER_EXECUTION_PENDING'))
                )
            )
        );

-- jOOQ's H2 DDL parser cannot model PostgreSQL trigger functions; PostgreSQL still applies the
-- complete claim-purpose provenance and same-state recovery guards below.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION guard_start_session_reservation_insert()
RETURNS trigger AS $$
BEGIN
    IF NEW.phase <> 'ACCOUNT_AUTHORIZATION'
        OR NEW.state <> 'RESERVED'
        OR NEW.reservation_owner_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.reservation_claim_fence <> 1
        OR NEW.claim_owner_id IS DISTINCT FROM NEW.reservation_owner_id
        OR NEW.claim_fence <> 1
        OR NEW.claim_state <> 'ACTIVE'
        OR NEW.claim_purpose IS DISTINCT FROM 'ORIGINAL'
        OR NEW.claim_expires_at_epoch_ms <= NEW.created_at_epoch_ms
        OR NEW.claim_expires_at_epoch_ms - NEW.created_at_epoch_ms > 30000
        OR NEW.post_authorization_execution_tuple_json IS NOT NULL
        OR NEW.owner_execution_handoff_id IS NOT NULL THEN
        RAISE EXCEPTION 'StartSession reservation must begin in the exact reserved state';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION guard_start_session_post_authorization_execution()
RETURNS trigger AS $$
DECLARE
    state_transition BOOLEAN;
    claim_unchanged BOOLEAN;
BEGIN
    IF NEW.control_plane_request_id IS DISTINCT FROM OLD.control_plane_request_id
        OR NEW.pre_authorization_tuple_json IS DISTINCT FROM OLD.pre_authorization_tuple_json
        OR NEW.mutation_digest IS DISTINCT FROM OLD.mutation_digest
        OR NEW.reservation_owner_id IS DISTINCT FROM OLD.reservation_owner_id
        OR NEW.reservation_claim_fence IS DISTINCT FROM OLD.reservation_claim_fence
        OR NEW.created_at_epoch_ms IS DISTINCT FROM OLD.created_at_epoch_ms THEN
        RAISE EXCEPTION 'StartSession reservation identity is immutable';
    END IF;
    IF NEW.updated_at_epoch_ms < OLD.updated_at_epoch_ms THEN
        RAISE EXCEPTION 'StartSession reservation update time cannot move backwards';
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

    state_transition := NEW.phase IS DISTINCT FROM OLD.phase OR NEW.state IS DISTINCT FROM OLD.state;
    claim_unchanged :=
        NEW.claim_owner_id IS NOT DISTINCT FROM OLD.claim_owner_id
        AND NEW.claim_fence = OLD.claim_fence
        AND NEW.claim_expires_at_epoch_ms = OLD.claim_expires_at_epoch_ms
        AND NEW.claim_state = OLD.claim_state
        AND NEW.claim_purpose IS NOT DISTINCT FROM OLD.claim_purpose;

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

    IF state_transition THEN
        IF NOT claim_unchanged THEN
            RAISE EXCEPTION 'StartSession phase transition must preserve claim provenance';
        END IF;
        IF OLD.phase = 'ACCOUNT_AUTHORIZATION' AND OLD.state = 'AUTHORIZATION_PENDING'
            AND NEW.phase = 'ACCOUNT_AUTHORIZATION' AND NEW.state = 'AUTHORIZED' THEN
            IF OLD.post_authorization_execution_tuple_json IS NOT NULL
                OR NEW.post_authorization_execution_tuple_json IS NULL
                OR NEW.owner_execution_handoff_id IS NOT NULL THEN
                RAISE EXCEPTION 'StartSession authorization enrichment shape is invalid';
            END IF;
        ELSIF OLD.phase = 'ACCOUNT_AUTHORIZATION' AND OLD.state = 'AUTHORIZED'
            AND NEW.phase = 'OWNER_EXECUTION' AND NEW.state = 'OWNER_EXECUTION_PENDING' THEN
            IF OLD.post_authorization_execution_tuple_json IS NULL
                OR NEW.post_authorization_execution_tuple_json
                    IS DISTINCT FROM OLD.post_authorization_execution_tuple_json
                OR NEW.owner_execution_handoff_id IS NULL
                OR OLD.owner_execution_handoff_id IS NOT NULL THEN
                RAISE EXCEPTION 'StartSession owner handoff shape is invalid';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF claim_unchanged THEN
        RETURN NEW;
    END IF;

    -- A live claim may be renewed only by its holder before expiry, preserving its state-scoped
    -- purpose and extending it by no more than the bounded 30-second lease.
    IF OLD.claim_state = 'ACTIVE'
        AND NEW.claim_state = 'ACTIVE'
        AND NEW.claim_owner_id = OLD.claim_owner_id
        AND NEW.claim_fence = OLD.claim_fence
        AND NEW.claim_purpose = OLD.claim_purpose
        AND OLD.claim_expires_at_epoch_ms > NEW.updated_at_epoch_ms
        AND NEW.claim_expires_at_epoch_ms > OLD.claim_expires_at_epoch_ms
        AND NEW.claim_expires_at_epoch_ms > NEW.updated_at_epoch_ms
        AND NEW.claim_expires_at_epoch_ms - NEW.updated_at_epoch_ms <= 30000 THEN
        RETURN NEW;
    END IF;

    -- Expiration advances the fence and clears only the current owner and claim purpose.
    IF OLD.claim_state = 'ACTIVE'
        AND OLD.claim_expires_at_epoch_ms <= NEW.updated_at_epoch_ms
        AND NEW.claim_state = 'EXPIRED'
        AND NEW.claim_owner_id IS NULL
        AND NEW.claim_fence = OLD.claim_fence + 1
        AND NEW.claim_expires_at_epoch_ms = OLD.claim_expires_at_epoch_ms
        AND NEW.claim_purpose IS NULL THEN
        RETURN NEW;
    END IF;

    -- Recovery may reassign only an expired lease, for a purpose determined by the durable state.
    IF (OLD.claim_state = 'EXPIRED'
            OR (OLD.claim_state = 'ACTIVE'
                AND OLD.claim_expires_at_epoch_ms <= NEW.updated_at_epoch_ms))
        AND OLD.state <> 'OWNER_EXECUTION_PENDING'
        AND NEW.claim_state = 'ACTIVE'
        AND NEW.claim_owner_id IS NOT NULL
        AND NEW.claim_owner_id <> OLD.reservation_owner_id
        AND NEW.claim_owner_id IS DISTINCT FROM OLD.claim_owner_id
        AND NEW.claim_fence = OLD.claim_fence + 1
        AND NEW.claim_expires_at_epoch_ms > NEW.updated_at_epoch_ms
        AND NEW.claim_expires_at_epoch_ms - NEW.updated_at_epoch_ms <= 30000
        AND (
            (OLD.state = 'RESERVED' AND NEW.claim_purpose = 'RESERVED_RECOVERY_ISSUE')
            OR (OLD.state = 'AUTHORIZATION_PENDING'
                AND NEW.claim_purpose = 'AUTHORIZATION_RECOVERY')
            OR (OLD.state = 'AUTHORIZED'
                AND NEW.claim_purpose = 'AUTHORIZED_RESPONSE_RECOVERY')
        ) THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'StartSession same-state claim transition is not permitted';
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]
