-- An admitted original source operation must remain locally completable or definitively abortable
-- while Account ordering runs outside SQL. Publication selection has its existing V43 interlock.
-- [jooq ignore start]
-- Keep retained-state preflight and guard installation atomic against both writer directions.
LOCK TABLE version IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE game_design_draft_commit_application_slot IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE game_design_authored_draft_publish_selection IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM game_design_draft_commit_application_slot s
        LEFT JOIN game_design_draft_commit c
          ON c.canonical_tenant_id = s.canonical_tenant_id
         AND c.canonical_version_id = s.canonical_version_id
         AND c.request_id = s.request_id AND c.commit_id = s.commit_id
        LEFT JOIN version v
          ON v.id = c.game_design_version_row_id
         AND v.tenant_id = c.game_design_version_tenant_key
         AND v.canonical_tenant_id = c.canonical_tenant_id
         AND v.canonical_version_id = c.canonical_version_id
         AND v.identity_source_game_row_id = c.source_game_row_id
         AND v.identity_source_game_tenant_key = c.source_game_tenant_key
         AND v.identity_source_provenance_kind = c.source_provenance_kind
        WHERE v.id IS NULL OR v.version_state IS DISTINCT FROM 'DRAFT'
    ) THEN
        RAISE EXCEPTION 'Retained Draft application slot lacks its exact DRAFT Version'
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
        SELECT 1 FROM game_design_draft_commit_application_slot slot
        JOIN game_design_authored_draft_publish_selection selection
          USING (canonical_tenant_id, canonical_version_id)
    ) THEN
        RAISE EXCEPTION 'Retained Draft application slot conflicts with authored publication selection'
            USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION reject_gd_version_lifecycle_during_draft_application() RETURNS trigger AS $$
BEGIN
    IF NEW.version_state IS NOT DISTINCT FROM OLD.version_state
        AND NEW.version_state_epoch IS NOT DISTINCT FROM OLD.version_state_epoch THEN
        RETURN NEW;
    END IF;
    IF EXISTS (
        SELECT 1 FROM game_design_draft_commit_application_slot s
        WHERE s.canonical_tenant_id = OLD.canonical_tenant_id
          AND s.canonical_version_id = OLD.canonical_version_id
    ) THEN
        RAISE EXCEPTION 'Active or unresolved Draft application prevents Version lifecycle changes'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_version_draft_application_lifecycle_interlock
    BEFORE UPDATE OF version_state, version_state_epoch ON version
    FOR EACH ROW
    EXECUTE FUNCTION reject_gd_version_lifecycle_during_draft_application();

CREATE FUNCTION serialize_gd_draft_application_version() RETURNS trigger AS $$
BEGIN
    -- Acquire the Version write lock before V42's parent-commit lock or any slot lock. A physical
    -- no-logical-change UPDATE creates an MVCC conflict for stale REPEATABLE READ/SERIALIZABLE
    -- lifecycle writers; a row lock alone would let their old snapshot miss a committed slot.
    -- All Version fields, including epoch, timestamps and history, retain their exact values.
    UPDATE version v SET version_state = v.version_state
    FROM game_design_draft_commit c
    WHERE c.canonical_tenant_id = NEW.canonical_tenant_id
      AND c.canonical_version_id = NEW.canonical_version_id
      AND c.request_id = NEW.request_id AND c.commit_id = NEW.commit_id
      AND v.id = c.game_design_version_row_id
      AND v.tenant_id = c.game_design_version_tenant_key
      AND v.canonical_tenant_id = c.canonical_tenant_id
      AND v.canonical_version_id = c.canonical_version_id
      AND v.identity_source_game_row_id = c.source_game_row_id
      AND v.identity_source_game_tenant_key = c.source_game_tenant_key
      AND v.identity_source_provenance_kind = c.source_provenance_kind
      AND v.version_state = 'DRAFT';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Draft application slot requires its exact current DRAFT Version'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- PostgreSQL runs same-kind triggers by name: this must precede both retained V42/V43 guards.
CREATE TRIGGER a_gd_draft_application_version_interlock
    BEFORE INSERT ON game_design_draft_commit_application_slot
    FOR EACH ROW
    EXECUTE FUNCTION serialize_gd_draft_application_version();

CREATE FUNCTION serialize_gd_authored_selection_version() RETURNS trigger AS $$
BEGIN
    -- The reverse publication ordering needs the same MVCC serialization: merely locking Version
    -- in V43 leaves a stale slot inserter's snapshot unaware of a newly committed selection.
    UPDATE version v SET version_state = v.version_state
    WHERE v.id = NEW.game_design_version_row_id
      AND v.tenant_id = NEW.game_design_version_tenant_key
      AND v.canonical_tenant_id = NEW.canonical_tenant_id
      AND v.canonical_version_id = NEW.canonical_version_id
      AND v.identity_source_game_row_id = NEW.source_game_row_id
      AND v.identity_source_game_tenant_key = NEW.source_game_tenant_key
      AND v.identity_source_provenance_kind = NEW.source_provenance_kind
      AND v.version_state = 'DRAFT'
      AND v.version_state_epoch = NEW.version_state_epoch;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Authored Draft selection requires its exact current DRAFT Version'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER a_gd_authored_selection_version_interlock
    BEFORE INSERT ON game_design_authored_draft_publish_selection
    FOR EACH ROW
    EXECUTE FUNCTION serialize_gd_authored_selection_version();
-- [jooq ignore stop]
