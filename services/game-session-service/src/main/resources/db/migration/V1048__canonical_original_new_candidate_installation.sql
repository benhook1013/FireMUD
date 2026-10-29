-- The first NEW session value is a distinct non-admitting candidate. Its exact bytes and
-- dispatch/readback lifecycle live on the transition row already captured by account-control
-- cohort snapshots. No second ledger or authenticated-session columns are introduced.

ALTER TABLE game_session_canonical_binding_transition
    ADD COLUMN original_new_candidate_bytes bytea,
    ADD COLUMN original_new_candidate_digest character varying(71),
    ADD COLUMN original_new_candidate_state character varying(32),
    ADD COLUMN original_new_candidate_readback_digest character varying(71);

ALTER TABLE game_session_canonical_binding_transition
    ADD CONSTRAINT chk_gs_original_new_candidate_installation CHECK (
        ((original_new_candidate_bytes IS NULL
                AND original_new_candidate_digest IS NULL
                AND original_new_candidate_state IS NULL
                AND original_new_candidate_readback_digest IS NULL)
            OR (original_new_candidate_bytes IS NOT NULL
                AND octet_length(original_new_candidate_bytes) BETWEEN 1 AND 524288
                AND original_new_candidate_digest IS NOT NULL
                AND original_new_candidate_digest
                    = 'sha256:' || encode(sha256(original_new_candidate_bytes), 'hex')
                AND original_new_candidate_state IS NOT NULL
                AND original_new_candidate_state IN ('DISPATCH_POSSIBLE', 'READBACK_CONFIRMED')
                AND ((original_new_candidate_state = 'DISPATCH_POSSIBLE'
                        AND original_new_candidate_readback_digest IS NULL)
                    OR (original_new_candidate_state = 'READBACK_CONFIRMED'
                        AND original_new_candidate_readback_digest IS NOT NULL
                        AND original_new_candidate_readback_digest ~ '^sha256:[0-9a-f]{64}$'))))
        AND (original_new_candidate_bytes IS NULL
            OR (expected_prior_binding_ref IS NULL
                AND expected_prior_binding_generation IS NULL
                AND status IN ('PREPARED', 'PROVISIONAL', 'COMMITTED', 'ABORTED', 'AMBIGUOUS')))
    );

-- The first write freezes the exact candidate before any Redis attempt. The only later change
-- records a positive owner readback digest. These columns are never reset or retargeted.
-- [jooq ignore start]
CREATE FUNCTION guard_gs_original_new_candidate_installation() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.original_new_candidate_bytes IS NOT NULL
           OR NEW.original_new_candidate_digest IS NOT NULL
           OR NEW.original_new_candidate_state IS NOT NULL
           OR NEW.original_new_candidate_readback_digest IS NOT NULL THEN
            RAISE EXCEPTION 'Original NEW candidate must be claimed by update of a PREPARED transition'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_candidate_immutable';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'TRUNCATE' THEN
        IF EXISTS (SELECT 1 FROM game_session_canonical_binding_transition
                    WHERE original_new_candidate_bytes IS NOT NULL) THEN
            RAISE EXCEPTION 'Original NEW candidate transition retention is held'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_candidate_retained';
        END IF;
        RETURN NULL;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.original_new_candidate_bytes IS NOT NULL THEN
            RAISE EXCEPTION 'Original NEW candidate transition retention is held'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_candidate_retained';
        END IF;
        RETURN OLD;
    END IF;
    IF ROW(NEW.original_new_candidate_bytes, NEW.original_new_candidate_digest)
       IS DISTINCT FROM ROW(OLD.original_new_candidate_bytes, OLD.original_new_candidate_digest) THEN
        IF OLD.original_new_candidate_bytes IS NOT NULL
           OR OLD.original_new_candidate_digest IS NOT NULL
           OR NEW.original_new_candidate_bytes IS NULL
           OR NEW.original_new_candidate_digest IS NULL
           OR NEW.original_new_candidate_state IS DISTINCT FROM 'DISPATCH_POSSIBLE'
           OR NEW.original_new_candidate_readback_digest IS NOT NULL
           OR NEW.expected_prior_binding_ref IS NOT NULL
           OR NEW.expected_prior_binding_generation IS NOT NULL
           OR NEW.status NOT IN ('PREPARED', 'PROVISIONAL') THEN
            RAISE EXCEPTION 'Original NEW candidate bytes are immutable after the first dispatch claim'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_candidate_immutable';
        END IF;
    END IF;
    IF NEW.original_new_candidate_state IS DISTINCT FROM OLD.original_new_candidate_state THEN
        IF OLD.original_new_candidate_state IS NULL
           AND NEW.original_new_candidate_state = 'DISPATCH_POSSIBLE'
           AND OLD.original_new_candidate_bytes IS NULL
           AND NEW.original_new_candidate_bytes IS NOT NULL
           AND NEW.original_new_candidate_readback_digest IS NULL
           AND NEW.expected_prior_binding_ref IS NULL
           AND NEW.expected_prior_binding_generation IS NULL
           AND NEW.status IN ('PREPARED', 'PROVISIONAL') THEN
            NULL;
        ELSIF OLD.original_new_candidate_state = 'DISPATCH_POSSIBLE'
           AND NEW.original_new_candidate_state = 'READBACK_CONFIRMED'
           AND NEW.original_new_candidate_bytes IS NOT DISTINCT FROM OLD.original_new_candidate_bytes
           AND NEW.original_new_candidate_digest IS NOT DISTINCT FROM OLD.original_new_candidate_digest
           AND NEW.original_new_candidate_readback_digest IS NOT NULL
           AND NEW.original_new_candidate_readback_digest ~ '^sha256:[0-9a-f]{64}$'
           AND NEW.status IN ('PREPARED', 'PROVISIONAL', 'COMMITTED', 'AMBIGUOUS') THEN
            NULL;
        ELSE
            RAISE EXCEPTION 'Original NEW candidate dispatch/readback state is not monotone'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_candidate_state_monotone';
        END IF;
    ELSIF NEW.original_new_candidate_readback_digest IS DISTINCT FROM OLD.original_new_candidate_readback_digest THEN
        RAISE EXCEPTION 'Original NEW candidate readback digest is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_candidate_readback_immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gs_original_new_candidate_installation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_canonical_binding_transition
    FOR EACH ROW EXECUTE FUNCTION guard_gs_original_new_candidate_installation();

CREATE TRIGGER gs_original_new_candidate_installation_retention
    BEFORE TRUNCATE ON game_session_canonical_binding_transition
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_original_new_candidate_installation();
-- [jooq ignore stop]
