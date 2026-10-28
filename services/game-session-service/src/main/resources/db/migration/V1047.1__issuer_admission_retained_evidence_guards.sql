-- Retain original issuer admission evidence. Only the existing owner's one-way completion
-- updates are allowed; insertion and current control authority remain with their existing owners.
-- [jooq ignore start]
CREATE FUNCTION guard_gs_issuer_admission_immutable_evidence() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Retained issuer admission evidence is immutable: %', TG_TABLE_NAME
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION guard_gs_issuer_admission_initializer() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Retained issuer admission initializer cannot be removed'
            USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - 'initialization_state')
            IS DISTINCT FROM (to_jsonb(OLD) - 'initialization_state')
       OR NOT ((OLD.initialization_state = 'PREPARED' AND NEW.initialization_state = 'INITIALIZING')
           OR (OLD.initialization_state = 'INITIALIZING' AND NEW.initialization_state = 'OPEN_CONFIRMED')) THEN
        RAISE EXCEPTION 'Retained issuer admission initializer permits only original forward completion'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION guard_gs_issuer_admission_cutoff_target() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Retained issuer admission cutoff target cannot be removed'
            USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['target_state', 'exact_closed_readback_bytes',
            'exact_closed_readback_digest', 'script_outcome'])
            IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['target_state', 'exact_closed_readback_bytes',
            'exact_closed_readback_digest', 'script_outcome'])
       OR OLD.target_state <> 'REQUIRED' OR NEW.target_state <> 'CLOSED'
       OR OLD.exact_closed_readback_bytes IS NOT NULL
       OR OLD.exact_closed_readback_digest IS NOT NULL OR OLD.script_outcome IS NOT NULL
       OR NEW.exact_closed_readback_bytes IS DISTINCT FROM OLD.closed_payload_bytes
       OR NEW.exact_closed_readback_digest IS DISTINCT FROM OLD.closed_payload_digest
       OR NEW.script_outcome IS NULL OR NEW.script_outcome NOT IN (1, 2, 3) THEN
        RAISE EXCEPTION 'Retained issuer admission cutoff target requires its one exact original readback'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION guard_gs_issuer_admission_operation() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Retained issuer admission operation cannot be removed'
            USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['operation_state', 'installed_at'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['operation_state', 'installed_at'])
       OR OLD.operation_kind <> 'CUTOFF'
       OR OLD.operation_state <> 'CUTOFF_PENDING' OR NEW.operation_state <> 'INSTALLED'
       OR OLD.installed_at IS NOT NULL OR NEW.installed_at IS NULL THEN
        RAISE EXCEPTION 'Retained issuer admission operation permits only original cutoff installation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gs_issuer_admission_initializer_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_admission_initializer
    FOR EACH ROW EXECUTE FUNCTION guard_gs_issuer_admission_initializer();
CREATE TRIGGER gs_issuer_admission_initializer_retention
    BEFORE TRUNCATE ON game_session_canonical_issuer_admission_initializer
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_issuer_admission_initializer();

CREATE TRIGGER gs_issuer_admission_receipt_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_admission_initializer_receipt
    FOR EACH ROW EXECUTE FUNCTION guard_gs_issuer_admission_immutable_evidence();
CREATE TRIGGER gs_issuer_admission_receipt_retention
    BEFORE TRUNCATE ON game_session_canonical_issuer_admission_initializer_receipt
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_issuer_admission_immutable_evidence();

CREATE TRIGGER gs_issuer_admission_cohort_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_admission_cohort_row
    FOR EACH ROW EXECUTE FUNCTION guard_gs_issuer_admission_immutable_evidence();
CREATE TRIGGER gs_issuer_admission_cohort_retention
    BEFORE TRUNCATE ON game_session_canonical_issuer_admission_cohort_row
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_issuer_admission_immutable_evidence();

CREATE TRIGGER gs_issuer_admission_cutoff_target_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_admission_cutoff_target
    FOR EACH ROW EXECUTE FUNCTION guard_gs_issuer_admission_cutoff_target();
CREATE TRIGGER gs_issuer_admission_cutoff_target_retention
    BEFORE TRUNCATE ON game_session_canonical_issuer_admission_cutoff_target
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_issuer_admission_cutoff_target();

CREATE TRIGGER gs_issuer_admission_operation_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_admission_operation
    FOR EACH ROW EXECUTE FUNCTION guard_gs_issuer_admission_operation();
CREATE TRIGGER gs_issuer_admission_operation_retention
    BEFORE TRUNCATE ON game_session_canonical_issuer_admission_operation
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_issuer_admission_operation();
-- [jooq ignore stop]
