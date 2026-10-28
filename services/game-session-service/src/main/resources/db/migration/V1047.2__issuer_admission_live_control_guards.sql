-- Live issuer admission authority has its own retained epoch domain. These guards constrain
-- existing owner updates; initial insertion does not gain any new activation authority.
-- [jooq ignore start]
-- A never-installed issuer starts at zero without a control row. Every allocated live epoch
-- otherwise has its matching control in the same committed transaction. Reject incompatible
-- retained data rather than manufacturing or resetting current authority during this upgrade.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM game_session_canonical_issuer_admission_epoch_counter counter
        FULL JOIN game_session_canonical_issuer_admission_control control USING (issuer_id)
        WHERE counter.issuer_id IS NULL
            OR (control.issuer_id IS NULL AND counter.last_live_barrier_epoch <> 0)
            OR (control.issuer_id IS NOT NULL
                AND counter.last_live_barrier_epoch <> control.live_barrier_epoch)
    ) THEN
        RAISE EXCEPTION 'Live issuer admission epoch counter and control must retain the same current epoch'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION guard_gs_issuer_admission_current_epoch_pair() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM game_session_canonical_issuer_admission_epoch_counter counter
        FULL JOIN game_session_canonical_issuer_admission_control control USING (issuer_id)
        WHERE COALESCE(counter.issuer_id, control.issuer_id) = NEW.issuer_id
            AND (counter.issuer_id IS NULL
                OR (control.issuer_id IS NULL AND counter.last_live_barrier_epoch <> 0)
                OR (control.issuer_id IS NOT NULL
                    AND counter.last_live_barrier_epoch <> control.live_barrier_epoch))
    ) THEN
        RAISE EXCEPTION 'Live issuer admission epoch counter and control must retain the same current epoch'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- The owner allocates before writing control. Validate the final pair at commit, not the
-- intermediate allocation, and cover inserts as well as updates of either side.
CREATE CONSTRAINT TRIGGER gs_issuer_admission_epoch_counter_current_pair
    AFTER INSERT OR UPDATE ON game_session_canonical_issuer_admission_epoch_counter
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION guard_gs_issuer_admission_current_epoch_pair();
CREATE CONSTRAINT TRIGGER gs_issuer_admission_live_control_current_pair
    AFTER INSERT OR UPDATE ON game_session_canonical_issuer_admission_control
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION guard_gs_issuer_admission_current_epoch_pair();

CREATE FUNCTION guard_gs_issuer_admission_epoch_counter() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Live issuer admission epoch counter cannot be removed'
            USING ERRCODE = '23514';
    END IF;
    -- Both initial installation and cutoff allocate exactly one epoch in the existing owner.
    IF (to_jsonb(NEW) - ARRAY['last_live_barrier_epoch', 'updated_at'])
            IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['last_live_barrier_epoch', 'updated_at'])
       OR NEW.last_live_barrier_epoch <> OLD.last_live_barrier_epoch + 1 THEN
        RAISE EXCEPTION 'Live issuer admission epoch counter must advance its original domain by one'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION guard_gs_issuer_admission_live_control() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Live issuer admission control cannot be removed'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.state = 'OPEN' AND NEW.state = 'CUTOFF_PENDING'
       AND OLD.current_operation_kind = 'INITIAL_ANCHOR_INSTALL'
       AND NEW.current_operation_kind = 'CUTOFF'
       AND NEW.current_operation_id IS DISTINCT FROM OLD.current_operation_id
       AND NEW.live_barrier_epoch = OLD.live_barrier_epoch + 1
       AND NEW.inventory_snapshot_revision >= OLD.inventory_snapshot_revision
       AND (to_jsonb(NEW) - ARRAY['state', 'live_barrier_epoch', 'current_operation_id',
            'current_operation_kind', 'inventory_snapshot_revision', 'cohort_row_count',
            'cohort_digest', 'control_exact_bytes', 'control_exact_digest', 'updated_at'])
           IS NOT DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['state', 'live_barrier_epoch', 'current_operation_id',
            'current_operation_kind', 'inventory_snapshot_revision', 'cohort_row_count',
            'cohort_digest', 'control_exact_bytes', 'control_exact_digest', 'updated_at']) THEN
        RETURN NEW;
    END IF;
    -- Completion publishes the captured cutoff, retaining its epoch, operation and whole cohort.
    IF OLD.state = 'CUTOFF_PENDING' AND NEW.state = 'INSTALLED'
       AND OLD.current_operation_kind = 'CUTOFF'
       AND (to_jsonb(NEW) - ARRAY['state', 'control_exact_bytes', 'control_exact_digest', 'updated_at'])
           IS NOT DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['state', 'control_exact_bytes', 'control_exact_digest', 'updated_at']) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Live issuer admission control permits only original forward cutoff transitions'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gs_issuer_admission_epoch_counter_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_admission_epoch_counter
    FOR EACH ROW EXECUTE FUNCTION guard_gs_issuer_admission_epoch_counter();
CREATE TRIGGER gs_issuer_admission_epoch_counter_retention
    BEFORE TRUNCATE ON game_session_canonical_issuer_admission_epoch_counter
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_issuer_admission_epoch_counter();

CREATE TRIGGER gs_issuer_admission_live_control_guard
    BEFORE UPDATE OR DELETE ON game_session_canonical_issuer_admission_control
    FOR EACH ROW EXECUTE FUNCTION guard_gs_issuer_admission_live_control();
CREATE TRIGGER gs_issuer_admission_live_control_retention
    BEFORE TRUNCATE ON game_session_canonical_issuer_admission_control
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_issuer_admission_live_control();
-- [jooq ignore stop]
