-- Additive write guards only; retained source rows receive no inferred provenance or rewrite.
-- [jooq ignore start]
CREATE FUNCTION enforce_gd_draft_commit_visibility_pointer_advance() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
            OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id THEN
            RAISE EXCEPTION 'Game Design Draft visibility pointer target is immutable'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.request_id IS NOT DISTINCT FROM OLD.request_id
            AND NEW.commit_id IS NOT DISTINCT FROM OLD.commit_id
            AND NEW.input_digest IS NOT DISTINCT FROM OLD.input_digest THEN
            RETURN NEW;
        END IF;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM game_design_draft_commit c
        JOIN game_design_draft_commit_application_slot s
          ON s.canonical_tenant_id = c.canonical_tenant_id
         AND s.canonical_version_id = c.canonical_version_id
         AND s.request_id = c.request_id
         AND s.commit_id = c.commit_id
        WHERE c.canonical_tenant_id = NEW.canonical_tenant_id
          AND c.canonical_version_id = NEW.canonical_version_id
          AND c.request_id = NEW.request_id
          AND c.commit_id = NEW.commit_id
          AND c.workflow_state IN ('APPLYING', 'RECONCILIATION_REQUIRED')
    ) THEN
        RAISE EXCEPTION 'Draft visibility pointer may advance only to its actively applying commit'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_visibility_pointer_advance
    BEFORE INSERT OR UPDATE ON game_design_draft_commit_visibility
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_commit_visibility_pointer_advance();

CREATE FUNCTION reject_gd_draft_commit_visibility_pointer_delete() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game Design Draft visibility pointers cannot be deleted'
        USING ERRCODE = 'check_violation';
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_visibility_pointer_no_delete
    BEFORE DELETE ON game_design_draft_commit_visibility
    FOR EACH ROW
    EXECUTE FUNCTION reject_gd_draft_commit_visibility_pointer_delete();

CREATE FUNCTION reject_gd_draft_commit_visibility_pointer_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM game_design_draft_commit_visibility) THEN
        RAISE EXCEPTION 'Game Design Draft visibility pointers cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_visibility_pointer_no_truncate
    BEFORE TRUNCATE ON game_design_draft_commit_visibility
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_gd_draft_commit_visibility_pointer_truncate();

CREATE FUNCTION enforce_realm_policy_source_head_insert_epoch() RETURNS trigger AS $$
BEGIN
    IF NEW.genesis_receipt_id IS NULL AND NOT EXISTS (
        SELECT 1
        FROM game_design_realm_policy_snapshot s
        WHERE s.canonical_tenant_id = NEW.canonical_tenant_id
          AND s.canonical_version_id = NEW.canonical_version_id
          AND s.commit_id = NEW.visible_commit_id
          AND s.snapshot_json::JSONB->>'sourceEpoch' = NEW.source_epoch
    ) THEN
        RAISE EXCEPTION 'realm policy source head must match its visible snapshot epoch'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_realm_policy_source_head_insert_epoch
    BEFORE INSERT ON game_design_realm_policy_source
    FOR EACH ROW
    EXECUTE FUNCTION enforce_realm_policy_source_head_insert_epoch();
-- [jooq ignore stop]
