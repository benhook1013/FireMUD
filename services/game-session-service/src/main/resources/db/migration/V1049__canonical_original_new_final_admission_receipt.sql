-- Actual original-NEW controller readback anchors. The complete authenticated value remains
-- in the canonical session key; SQL retains only its digest and immutable controller times.
ALTER TABLE game_session_canonical_binding_transition
    ADD COLUMN original_new_final_session_sha256 character varying(64),
    ADD COLUMN original_new_admission_at_ms bigint,
    ADD COLUMN original_new_continuity_expires_at_ms bigint,
    ADD COLUMN original_new_session_duration_ms bigint;

ALTER TABLE game_session_canonical_binding_transition
    ADD CONSTRAINT chk_gs_original_new_final_admission CHECK ((
        (original_new_final_session_sha256 IS NULL
            AND original_new_admission_at_ms IS NULL
            AND original_new_continuity_expires_at_ms IS NULL
            AND original_new_session_duration_ms IS NULL)
        OR (original_new_final_session_sha256 IS NOT NULL
            AND original_new_final_session_sha256 ~ '^[0-9a-f]{64}$'
            AND original_new_admission_at_ms IS NOT NULL AND original_new_admission_at_ms > 0
            AND original_new_continuity_expires_at_ms IS NOT NULL
            AND original_new_session_duration_ms IS NOT NULL
            AND original_new_session_duration_ms BETWEEN 1 AND 300000
            AND original_new_admission_at_ms <= 9223372036854475807
            AND original_new_continuity_expires_at_ms
                = original_new_admission_at_ms + original_new_session_duration_ms
            AND original_new_admission_at_ms < admission_lease_expires_at
            AND admission_lease_id IS NOT NULL
            AND admission_lease_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND admission_lease_digest ~ '^[0-9a-f]{64}$'
            AND admission_lease_evidence IS NOT NULL
            AND admission_lease_fence > 0
            AND admission_lease_expires_at > 0
            AND admission_lease_kind = 'NEW_BINDING'
            AND admission_resume_episode_id IS NULL
            AND admission_expected_old_binding_generation IS NULL
            AND original_new_candidate_state = 'READBACK_CONFIRMED'
            AND original_new_candidate_digest ~ '^sha256:[0-9a-f]{64}$'
            AND original_new_candidate_readback_digest ~ '^sha256:[0-9a-f]{64}$'
            AND original_new_candidate_bytes IS NOT NULL
            AND expected_prior_binding_ref IS NULL
            AND expected_prior_binding_generation IS NULL
            AND status = 'COMMITTED')
    ) IS TRUE);

-- [jooq ignore start]
CREATE FUNCTION guard_gs_original_new_final_admission() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.original_new_final_session_sha256 IS NOT NULL
            OR NEW.original_new_admission_at_ms IS NOT NULL
            OR NEW.original_new_continuity_expires_at_ms IS NOT NULL
            OR NEW.original_new_session_duration_ms IS NOT NULL THEN
            RAISE EXCEPTION 'Original NEW final receipt requires an existing exact provisional source'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_final_receipt_source';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.original_new_final_session_sha256 IS NOT NULL THEN
        IF ROW(NEW.original_new_final_session_sha256, NEW.original_new_admission_at_ms,
                NEW.original_new_continuity_expires_at_ms, NEW.original_new_session_duration_ms)
            IS DISTINCT FROM ROW(OLD.original_new_final_session_sha256, OLD.original_new_admission_at_ms,
                OLD.original_new_continuity_expires_at_ms, OLD.original_new_session_duration_ms)
            OR ROW(NEW.original_new_candidate_bytes, NEW.original_new_candidate_digest,
                NEW.original_new_candidate_state, NEW.original_new_candidate_readback_digest,
                NEW.admission_lease_id, NEW.admission_lease_digest, NEW.admission_lease_evidence,
                NEW.admission_lease_fence, NEW.admission_lease_expires_at, NEW.admission_request_id)
            IS DISTINCT FROM ROW(OLD.original_new_candidate_bytes, OLD.original_new_candidate_digest,
                OLD.original_new_candidate_state, OLD.original_new_candidate_readback_digest,
                OLD.admission_lease_id, OLD.admission_lease_digest, OLD.admission_lease_evidence,
                OLD.admission_lease_fence, OLD.admission_lease_expires_at, OLD.admission_request_id) THEN
            RAISE EXCEPTION 'Original NEW final receipt is immutable'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_final_receipt_immutable';
        END IF;
    ELSIF NEW.original_new_final_session_sha256 IS NOT NULL THEN
        IF OLD.status IS DISTINCT FROM 'PROVISIONAL' OR NEW.status IS DISTINCT FROM 'COMMITTED'
            OR OLD.admission_lease_id IS NULL
            OR OLD.admission_lease_id = '00000000-0000-0000-0000-000000000000'::uuid
            OR OLD.admission_lease_digest IS NULL
            OR OLD.admission_lease_evidence IS NULL
            OR OLD.admission_lease_expires_at IS NULL
            OR OLD.admission_lease_expires_at <= 0
            OR OLD.admission_lease_kind IS DISTINCT FROM 'NEW_BINDING'
            OR OLD.original_new_candidate_digest IS NULL
            OR OLD.original_new_candidate_readback_digest IS NULL
            OR OLD.original_new_candidate_state IS DISTINCT FROM 'READBACK_CONFIRMED'
            OR OLD.original_new_candidate_bytes IS NULL
            OR OLD.expected_prior_binding_ref IS NOT NULL
            OR OLD.expected_prior_binding_generation IS NOT NULL
            OR NEW.original_new_candidate_bytes IS DISTINCT FROM OLD.original_new_candidate_bytes
            OR NEW.original_new_candidate_digest IS DISTINCT FROM OLD.original_new_candidate_digest
            OR NEW.original_new_candidate_readback_digest IS DISTINCT FROM OLD.original_new_candidate_readback_digest
            OR NEW.admission_lease_expires_at IS DISTINCT FROM OLD.admission_lease_expires_at
            OR NEW.admission_lease_fence IS DISTINCT FROM OLD.admission_lease_fence
            OR NEW.admission_request_id IS DISTINCT FROM OLD.admission_request_id
            OR NEW.admission_lease_id IS DISTINCT FROM OLD.admission_lease_id
            OR NEW.admission_lease_digest IS DISTINCT FROM OLD.admission_lease_digest
            OR NEW.admission_lease_evidence IS DISTINCT FROM OLD.admission_lease_evidence THEN
            RAISE EXCEPTION 'Original NEW final receipt cannot replace its original provisional source'
                USING ERRCODE = '23514', CONSTRAINT = 'gs_original_new_final_receipt_source';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER gs_original_new_final_admission_guard
    BEFORE INSERT OR UPDATE ON game_session_canonical_binding_transition
    FOR EACH ROW EXECUTE FUNCTION guard_gs_original_new_final_admission();
-- [jooq ignore stop]
