-- Exact original NEW negative selection retains the original candidate and its one-create evidence.
ALTER TABLE game_session_canonical_binding_transition
    ADD COLUMN original_new_abandonment_id uuid,
    ADD COLUMN original_new_abandonment_state character varying(32),
    ADD COLUMN original_new_abandonment_closure_digest character varying(71),
    ADD COLUMN original_new_abandonment_orphan_cleanup_id uuid,
    ADD COLUMN original_new_abandonment_resolution_id uuid;

ALTER TABLE game_session_canonical_binding_transition
    ADD CONSTRAINT chk_original_new_abandonment CHECK (
      (original_new_abandonment_id IS NULL
        AND original_new_abandonment_state IS NULL
        AND original_new_abandonment_closure_digest IS NULL
        AND original_new_abandonment_orphan_cleanup_id IS NULL
        AND original_new_abandonment_resolution_id IS NULL)
      OR (original_new_abandonment_id IS NOT NULL
        AND original_new_abandonment_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND original_new_abandonment_state IS NOT NULL
        AND original_new_candidate_state = 'DISPATCH_POSSIBLE'
        AND original_new_candidate_bytes IS NOT NULL
        AND original_new_candidate_readback_digest IS NULL
        AND expected_prior_binding_ref IS NULL AND expected_prior_binding_generation IS NULL
        AND admission_lease_id IS NULL
        AND status IN ('PREPARED', 'ABORTED')
        AND ((original_new_abandonment_state = 'ABANDONMENT_SELECTED'
              AND original_new_abandonment_closure_digest IS NULL)
          OR (original_new_abandonment_state = 'NEVER_ADMITTED_CLOSED'
              AND original_new_abandonment_closure_digest IS NOT NULL
              AND original_new_abandonment_closure_digest ~ '^sha256:[0-9a-f]{64}$'))
        AND ((status = 'PREPARED' AND original_new_abandonment_orphan_cleanup_id IS NULL
              AND original_new_abandonment_resolution_id IS NULL)
          OR (status = 'ABORTED' AND original_new_abandonment_state = 'NEVER_ADMITTED_CLOSED'
              AND original_new_abandonment_orphan_cleanup_id IS NOT NULL
              AND original_new_abandonment_orphan_cleanup_id <> '00000000-0000-0000-0000-000000000000'::uuid
              AND original_new_abandonment_resolution_id IS NOT NULL
              AND original_new_abandonment_resolution_id <> '00000000-0000-0000-0000-000000000000'::uuid))));

-- [jooq ignore start]
CREATE FUNCTION guard_original_new_abandonment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'TRUNCATE' THEN
    IF EXISTS (SELECT 1 FROM game_session_canonical_binding_transition WHERE original_new_abandonment_id IS NOT NULL) THEN
      RAISE EXCEPTION 'Original NEW abandonment retention is held' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
  END IF;
  IF TG_OP = 'DELETE' THEN
    IF OLD.original_new_abandonment_id IS NOT NULL THEN
      RAISE EXCEPTION 'Original NEW abandonment retention is held' USING ERRCODE = '23514';
    END IF;
    RETURN OLD;
  END IF;
  IF OLD.original_new_abandonment_id IS NULL AND NEW.original_new_abandonment_id IS NOT NULL THEN
    IF OLD.status <> 'PREPARED' OR NEW.status <> 'PREPARED'
        OR OLD.original_new_candidate_state IS DISTINCT FROM 'DISPATCH_POSSIBLE'
        OR OLD.original_new_candidate_readback_digest IS NOT NULL
        OR OLD.admission_lease_id IS NOT NULL
        OR NEW.original_new_abandonment_state IS DISTINCT FROM 'ABANDONMENT_SELECTED'
        OR EXISTS (SELECT 1 FROM game_session_canonical_account_member_repair_operation WHERE transition_id = OLD.transition_id)
        OR EXISTS (SELECT 1 FROM game_session_region_timeline_preparation WHERE transition_id = OLD.transition_id)
        OR EXISTS (SELECT 1 FROM game_session_canonical_binding_account_index_obligation
                   WHERE transition_id = OLD.transition_id AND (status <> 'REQUIRED' OR projection_state <> 'REQUIRED'))
        OR EXISTS (SELECT 1 FROM game_session_canonical_binding_region_bridge_obligation
                   WHERE transition_id = OLD.transition_id AND (status <> 'REQUIRED' OR bridge_operation_id IS NOT NULL)) THEN
      RAISE EXCEPTION 'Only an untouched PREPARED dispatch may select negative settlement' USING ERRCODE = '23514';
    END IF;
  ELSIF OLD.original_new_abandonment_id IS NOT NULL THEN
    IF NEW.original_new_abandonment_id IS DISTINCT FROM OLD.original_new_abandonment_id
        OR NEW.status NOT IN ('PREPARED', 'ABORTED')
        OR (OLD.status = 'ABORTED' AND NEW.status <> 'ABORTED')
        OR (OLD.original_new_abandonment_state = 'NEVER_ADMITTED_CLOSED'
            AND ROW(NEW.original_new_abandonment_state, NEW.original_new_abandonment_closure_digest)
                IS DISTINCT FROM ROW(OLD.original_new_abandonment_state, OLD.original_new_abandonment_closure_digest))
        OR (OLD.original_new_abandonment_orphan_cleanup_id IS NOT NULL
            AND ROW(NEW.original_new_abandonment_orphan_cleanup_id, NEW.original_new_abandonment_resolution_id)
                IS DISTINCT FROM ROW(OLD.original_new_abandonment_orphan_cleanup_id, OLD.original_new_abandonment_resolution_id)) THEN
      RAISE EXCEPTION 'Original NEW negative selection and receipts are irreversible' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER original_new_abandonment_guard BEFORE UPDATE OR DELETE ON game_session_canonical_binding_transition
    FOR EACH ROW EXECUTE FUNCTION guard_original_new_abandonment();
CREATE TRIGGER original_new_abandonment_retention BEFORE TRUNCATE ON game_session_canonical_binding_transition
    FOR EACH STATEMENT EXECUTE FUNCTION guard_original_new_abandonment();
-- [jooq ignore stop]
