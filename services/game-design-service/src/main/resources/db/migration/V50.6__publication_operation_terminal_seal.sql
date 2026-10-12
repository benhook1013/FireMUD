ALTER TABLE publish_attempt ADD COLUMN revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0);

CREATE TABLE game_design_publication_operation (
    publish_workflow_id VARCHAR(1024) PRIMARY KEY REFERENCES publish_attempt(publish_workflow_id) ON DELETE RESTRICT,
    tenant_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL REFERENCES version(id) ON DELETE RESTRICT,
    selection_digest VARCHAR(71) NOT NULL,
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) > 0),
    outcome TEXT NOT NULL DEFAULT 'PENDING' CHECK (outcome IN ('PENDING', 'PUBLISHED', 'NO_PUBLICATION')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    result_bytes BYTEA,
    release_row_json TEXT,
    CHECK ((outcome = 'PENDING') = (result_bytes IS NULL)),
    CHECK ((outcome = 'PUBLISHED') = (release_row_json IS NOT NULL)),
    UNIQUE (tenant_id, version_id)
);

-- [jooq ignore start]
CREATE FUNCTION guard_game_design_publication_operation() RETURNS trigger AS $$
DECLARE attempt publish_attempt%ROWTYPE; selected game_design_authored_draft_publish_selection%ROWTYPE;
    account_bytes BYTEA; input_bytes BYTEA; world_json JSONB;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'publication operation history is immutable' USING ERRCODE = 'check_violation';
    END IF;
    PERFORM 1 FROM game WHERE tenant_id = NEW.tenant_id FOR UPDATE;
    SELECT * INTO STRICT attempt FROM publish_attempt
        WHERE publish_workflow_id = NEW.publish_workflow_id FOR UPDATE;
    PERFORM 1 FROM version WHERE id = NEW.version_id AND tenant_id = NEW.tenant_id FOR UPDATE;
    SELECT * INTO STRICT selected FROM game_design_authored_draft_publish_selection
        WHERE game_design_version_tenant_key = NEW.tenant_id AND game_design_version_row_id = NEW.version_id;
    account_bytes := published_world_selector_frame(NEW.request_bytes, 1);
    input_bytes := published_world_selector_frame(account_bytes, 3);
    world_json := convert_from(published_world_selector_frame(NEW.request_bytes, 2), 'UTF8')::JSONB;
    IF convert_from(published_world_selector_frame(NEW.request_bytes, 0), 'UTF8') <> 'game-design-publication-operation/v1'
        OR convert_from(published_world_selector_frame(account_bytes, 0), 'UTF8') <> 'account-publication-authorization/v1'
        OR convert_from(published_world_selector_frame(input_bytes, 0), 'UTF8') <> 'account-publication-input/v1'
        OR published_world_selector_frame(input_bytes, 2) IS DISTINCT FROM convert_to(selected.selection_json, 'UTF8')
        OR convert_from(published_world_selector_frame(input_bytes, 3), 'UTF8') IS DISTINCT FROM selected.selection_digest
        OR world_json->'request'->>'requestDigest' IS DISTINCT FROM substring(selected.selection_digest FROM 8)
        OR world_json->'request'->>'canonicalTenantId' IS DISTINCT FROM selected.canonical_tenant_id::TEXT
        OR world_json->'request'->>'canonicalVersionId' IS DISTINCT FROM selected.canonical_version_id::TEXT
        OR world_json->'request'->>'publishWorkflowId' IS DISTINCT FROM NEW.publish_workflow_id
        OR world_json->'request'->>'publicationRequestId' IS DISTINCT FROM selected.publish_request_id
        OR world_json->'request'->>'versionStateEpoch' IS DISTINCT FROM selected.version_state_epoch::TEXT THEN
        RAISE EXCEPTION 'publication operation bytes differ from exact owner selection' USING ERRCODE = 'check_violation';
    END IF;
    IF attempt.tenant_id <> NEW.tenant_id OR attempt.version_id <> NEW.version_id
        OR attempt.publish_type <> 'FULL_VERSION' OR attempt.request_digest IS DISTINCT FROM NEW.selection_digest
        OR NOT EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection s
            WHERE s.game_design_version_tenant_key = NEW.tenant_id
                AND s.game_design_version_row_id = NEW.version_id AND s.selection_digest = NEW.selection_digest) THEN
        RAISE EXCEPTION 'publication operation does not bind exact selected attempt' USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.outcome <> 'PENDING' OR NEW.revision <> 1 OR attempt.status <> 'PENDING'
            OR EXISTS (SELECT 1 FROM published_release_bundle WHERE tenant_id = NEW.tenant_id AND version_id = NEW.version_id) THEN
            RAISE EXCEPTION 'historical attempts cannot acquire publication authority' USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        IF OLD.outcome <> 'PENDING' OR NEW.outcome = 'PENDING'
            OR NEW.revision <> OLD.revision + 1
            OR (NEW.publish_workflow_id, NEW.tenant_id, NEW.version_id, NEW.selection_digest, NEW.request_bytes)
                IS DISTINCT FROM (OLD.publish_workflow_id, OLD.tenant_id, OLD.version_id, OLD.selection_digest, OLD.request_bytes) THEN
            RAISE EXCEPTION 'publication operation identity or terminal outcome is immutable' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER game_design_publication_operation_guard BEFORE INSERT OR UPDATE OR DELETE
    ON game_design_publication_operation FOR EACH ROW EXECUTE FUNCTION guard_game_design_publication_operation();

CREATE FUNCTION guard_selected_publication_release() RETURNS trigger AS $$
DECLARE attempt publish_attempt%ROWTYPE; operation game_design_publication_operation%ROWTYPE;
BEGIN
    IF NEW.script_only THEN RETURN NEW; END IF;
    -- Old unselected history keeps its original read semantics; no operation is backfilled.
    IF NEW.attestation_schema_version <> 'v2' AND NOT EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection
        WHERE game_design_version_tenant_key = NEW.tenant_id AND game_design_version_row_id = NEW.version_id) THEN
        RETURN NEW;
    END IF;
    PERFORM 1 FROM game WHERE tenant_id = NEW.tenant_id FOR UPDATE;
    SELECT * INTO STRICT attempt FROM publish_attempt WHERE publish_workflow_id = NEW.publish_workflow_id FOR UPDATE;
    PERFORM 1 FROM version WHERE id = NEW.version_id AND tenant_id = NEW.tenant_id FOR UPDATE;
    SELECT * INTO STRICT operation FROM game_design_publication_operation WHERE publish_workflow_id = NEW.publish_workflow_id;
    IF attempt.status <> 'PENDING' OR operation.outcome <> 'PENDING'
        OR operation.tenant_id <> NEW.tenant_id OR operation.version_id <> NEW.version_id
        OR attempt.request_digest IS DISTINCT FROM operation.selection_digest
        OR NEW.attestation_schema_version <> 'v2'
        OR convert_to(NEW.world_published_start_location_evidence_json, 'UTF8')
            IS DISTINCT FROM published_world_selector_frame(operation.request_bytes, 2) THEN
        RAISE EXCEPTION 'selected publication is absent, sealed, or changed' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER selected_publication_release_guard BEFORE INSERT ON published_release_bundle
    FOR EACH ROW EXECUTE FUNCTION guard_selected_publication_release();

CREATE FUNCTION verify_publication_operation_commit() RETURNS trigger AS $$
DECLARE operation game_design_publication_operation%ROWTYPE; release_json TEXT; attempt_status TEXT;
BEGIN
    SELECT * INTO operation FROM game_design_publication_operation WHERE publish_workflow_id = NEW.publish_workflow_id;
    IF NOT FOUND THEN RETURN NULL; END IF;
    SELECT status INTO attempt_status FROM publish_attempt WHERE publish_workflow_id = operation.publish_workflow_id;
    SELECT to_jsonb(r)::TEXT INTO release_json FROM published_release_bundle r
        WHERE r.tenant_id = operation.tenant_id AND r.version_id = operation.version_id;
    IF operation.outcome <> 'PENDING' AND operation.result_bytes IS DISTINCT FROM
        int4send(octet_length(convert_to('game-design-publication-owner-readback/v1', 'UTF8')))
        || convert_to('game-design-publication-owner-readback/v1', 'UTF8')
        || int4send(octet_length(operation.request_bytes)) || operation.request_bytes
        || int4send(octet_length(convert_to(operation.outcome, 'UTF8'))) || convert_to(operation.outcome, 'UTF8')
        || int4send(octet_length(convert_to(COALESCE(operation.release_row_json, ''), 'UTF8')))
        || convert_to(COALESCE(operation.release_row_json, ''), 'UTF8') THEN
        RAISE EXCEPTION 'publication result bytes are not the exact owner receipt' USING ERRCODE = 'check_violation';
    END IF;
    IF operation.outcome = 'PENDING' AND (release_json IS NOT NULL OR attempt_status <> 'PENDING') THEN
        RAISE EXCEPTION 'selected publication must commit receipt and owner result together' USING ERRCODE = 'check_violation';
    ELSIF operation.outcome = 'NO_PUBLICATION' AND (release_json IS NOT NULL OR attempt_status <> 'FAILED') THEN
        RAISE EXCEPTION 'no-publication seal contradicts owner commit' USING ERRCODE = 'check_violation';
    ELSIF operation.outcome = 'PUBLISHED' AND (release_json IS DISTINCT FROM operation.release_row_json
        OR attempt_status <> 'SUCCEEDED'
        OR NOT EXISTS (SELECT 1 FROM version WHERE id = operation.version_id AND tenant_id = operation.tenant_id AND version_state = 'PUBLISHED')
        OR NOT EXISTS (SELECT 1 FROM version_asset_artifact WHERE tenant_id = operation.tenant_id AND version_id = operation.version_id AND artifact_state = 'PUBLISHED')) THEN
        RAISE EXCEPTION 'publication receipt lacks atomic owner result' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER publication_operation_commit_guard AFTER INSERT OR UPDATE
    ON game_design_publication_operation DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_publication_operation_commit();
CREATE CONSTRAINT TRIGGER selected_publication_release_commit_guard AFTER INSERT
    ON published_release_bundle DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_publication_operation_commit();
CREATE CONSTRAINT TRIGGER selected_publication_attempt_commit_guard AFTER UPDATE
    ON publish_attempt DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_publication_operation_commit();

CREATE FUNCTION guard_publish_attempt_revision() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.revision <> OLD.revision + 1
        OR (NEW.id, NEW.tenant_id, NEW.publish_workflow_id, NEW.publish_type, NEW.version_id,
            NEW.version_number, NEW.script_patch_version, NEW.base_version_id, NEW.created_at)
            IS DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.publish_workflow_id, OLD.publish_type, OLD.version_id,
                OLD.version_number, OLD.script_patch_version, OLD.base_version_id, OLD.created_at)
        OR (NEW.request_digest IS DISTINCT FROM OLD.request_digest AND
            (OLD.request_digest IS NOT NULL OR EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection
                WHERE game_design_version_tenant_key = OLD.tenant_id AND game_design_version_row_id = OLD.version_id))) THEN
        RAISE EXCEPTION 'publish attempt is stale or terminal' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER publish_attempt_revision_guard BEFORE UPDATE ON publish_attempt
    FOR EACH ROW EXECUTE FUNCTION guard_publish_attempt_revision();

CREATE FUNCTION guard_sealed_candidate_admission() RETURNS trigger AS $$
BEGIN
    -- Private puts already in flight may finish; this guards admission/retained candidate changes,
    -- not cancellation, reachability, abandonment, deletion, or distributed object-store writes.
    IF NEW.artifact_state = 'STAGED' AND EXISTS (SELECT 1 FROM game_design_publication_operation
        WHERE tenant_id = NEW.tenant_id AND version_id = NEW.version_id AND outcome <> 'PENDING') THEN
        RAISE EXCEPTION 'sealed publication refuses candidate admission' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER sealed_candidate_admission_guard BEFORE INSERT OR UPDATE ON version_asset_artifact
    FOR EACH ROW EXECUTE FUNCTION guard_sealed_candidate_admission();

CREATE FUNCTION forbid_publication_operation_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'publication operation history cannot be truncated' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER publication_operation_no_truncate BEFORE TRUNCATE ON game_design_publication_operation
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_publication_operation_truncate();
-- [jooq ignore stop]
