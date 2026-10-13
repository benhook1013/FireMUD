-- New source applications require the exact reviewed-base evidence reserved for their Draft.
-- Retained applications and historical bindings are not backfilled or rewritten.
-- [jooq ignore start]
CREATE FUNCTION require_reviewed_source_application_base() RETURNS trigger AS $$
BEGIN
    IF TG_TABLE_NAME = 'game_design_realm_policy_application' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM game_design_draft_commit c
            JOIN game_design_reviewed_draft_base b
                ON b.canonical_tenant_id = c.canonical_tenant_id
                AND b.canonical_version_id = c.canonical_version_id
                AND b.request_id = c.request_id
                AND b.commit_id = c.commit_id
                AND b.input_digest = c.input_digest
                AND b.binding_json = c.binding_json
            WHERE c.canonical_tenant_id = NEW.canonical_tenant_id
                AND c.canonical_version_id = NEW.canonical_version_id
                AND c.request_id = NEW.request_id
                AND c.commit_id = NEW.commit_id
        ) THEN
            RAISE EXCEPTION 'Draft source application requires exact reviewed base evidence'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF TG_TABLE_NAME = 'game_design_command_source_application' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM game_design_draft_commit c
            JOIN game_design_reviewed_draft_base b
                ON b.canonical_tenant_id = c.canonical_tenant_id
                AND b.canonical_version_id = c.canonical_version_id
                AND b.request_id = c.request_id
                AND b.commit_id = c.commit_id
                AND b.input_digest = c.input_digest
                AND b.binding_json = c.binding_json
            WHERE c.canonical_tenant_id = NEW.canonical_tenant_id
                AND c.canonical_version_id = NEW.canonical_version_id
                AND c.request_id = NEW.request_id
                AND c.commit_id = NEW.commit_id
                AND c.input_digest = NEW.input_digest
                AND c.binding_json = NEW.binding_json
                AND b.input_digest = NEW.input_digest
                AND b.binding_json = NEW.binding_json
        ) THEN
            RAISE EXCEPTION 'Draft source application requires exact reviewed base evidence'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'Reviewed base guard is bound to an unsupported source application table'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reviewed_realm_policy_application_guard
    BEFORE INSERT ON game_design_realm_policy_application
    FOR EACH ROW EXECUTE FUNCTION require_reviewed_source_application_base();
CREATE TRIGGER reviewed_command_source_application_guard
    BEFORE INSERT ON game_design_command_source_application
    FOR EACH ROW EXECUTE FUNCTION require_reviewed_source_application_base();
-- [jooq ignore stop]
