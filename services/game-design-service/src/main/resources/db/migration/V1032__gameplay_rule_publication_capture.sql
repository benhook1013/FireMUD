-- Freeze the original selected Gameplay snapshot under its exact pending publication operation.
CREATE TABLE game_design_gameplay_rule_source_capture (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    publish_workflow_id VARCHAR(1024) NOT NULL UNIQUE
        REFERENCES game_design_publication_operation (publish_workflow_id) ON DELETE RESTRICT,
    commit_id UUID NOT NULL,
    operation_bytes BYTEA NOT NULL CHECK (octet_length(operation_bytes) > 0),
    capture_bytes BYTEA NOT NULL CHECK (octet_length(capture_bytes) > 0),
    capture_digest VARCHAR(71) NOT NULL CHECK (capture_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_gameplay_rule_snapshot (canonical_tenant_id, canonical_version_id, commit_id)
        ON DELETE RESTRICT
);

-- [jooq ignore start]
CREATE FUNCTION guard_gameplay_rule_source_capture() RETURNS trigger AS $$
DECLARE operation game_design_publication_operation%ROWTYPE;
    selected game_design_authored_draft_publish_selection%ROWTYPE;
    owner_version version%ROWTYPE;
    source_snapshot game_design_gameplay_rule_snapshot%ROWTYPE;
    source_commit game_design_draft_commit%ROWTYPE;
    source_head game_design_gameplay_rule_head%ROWTYPE;
    expected BYTEA;
    bytes BYTEA;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'gameplay-rule publication source capture is immutable' USING ERRCODE = '23514';
    END IF;
    PERFORM require_selected_inventory_operation_v2(NEW.operation_bytes);
    SELECT * INTO STRICT operation FROM game_design_publication_operation
        WHERE publish_workflow_id = NEW.publish_workflow_id FOR UPDATE;
    SELECT * INTO STRICT selected FROM game_design_authored_draft_publish_selection
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id;
    SELECT owner.* INTO STRICT owner_version FROM version owner JOIN game source_game
        ON source_game.id = owner.identity_source_game_row_id
            AND source_game.tenant_id = owner.identity_source_game_tenant_key
            AND source_game.tenant_id = owner.tenant_id
            AND source_game.canonical_tenant_id = owner.canonical_tenant_id
            AND source_game.tenant_identity_provenance_kind = owner.identity_source_provenance_kind
            AND source_game.tenant_identity_source_game_id = source_game.id
            AND source_game.tenant_identity_source_legacy_tenant_id = source_game.tenant_id
        WHERE owner.canonical_tenant_id = NEW.canonical_tenant_id
            AND owner.canonical_version_id = NEW.canonical_version_id
            AND owner.id = selected.game_design_version_row_id
            AND owner.tenant_id = selected.game_design_version_tenant_key
            AND owner.identity_source_game_row_id = selected.source_game_row_id
            AND owner.identity_source_game_tenant_key = selected.source_game_tenant_key
            AND owner.identity_source_provenance_kind = selected.source_provenance_kind
        FOR UPDATE OF owner;
    IF owner_version.version_state <> 'DRAFT' OR owner_version.is_script_only
        OR owner_version.base_version_id IS NOT NULL OR owner_version.script_patch_version IS NOT NULL THEN
        RAISE EXCEPTION 'gameplay-rule capture requires exact canonical full Draft' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT source_snapshot FROM game_design_gameplay_rule_snapshot
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT source_commit FROM game_design_draft_commit
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id
            AND request_id = source_snapshot.request_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT source_head FROM game_design_gameplay_rule_head
        WHERE canonical_tenant_id = NEW.canonical_tenant_id
            AND canonical_version_id = NEW.canonical_version_id;

    bytes := convert_to('game-design-gameplay-rule-source-capture/v1', 'UTF8');
    expected := int4send(octet_length(bytes)) || bytes;
    expected := expected || int4send(octet_length(NEW.operation_bytes)) || NEW.operation_bytes;
    bytes := convert_to(source_snapshot.snapshot_json, 'UTF8');
    expected := expected || int4send(octet_length(bytes)) || bytes;

    IF operation.outcome <> 'PENDING'
        OR operation.request_bytes IS DISTINCT FROM NEW.operation_bytes
        OR operation.version_id <> owner_version.id OR operation.tenant_id <> owner_version.tenant_id
        OR operation.selection_digest IS DISTINCT FROM selected.selection_digest
        OR selected.game_design_version_row_id <> owner_version.id
        OR selected.game_design_version_tenant_key <> owner_version.tenant_id
        OR selected.version_state_epoch <> owner_version.version_state_epoch
        OR selected.selected_commit_id <> NEW.commit_id
        OR selected.selected_commit_request_id <> source_snapshot.request_id
        OR selected.selected_commit_digest IS DISTINCT FROM source_commit.input_digest
        OR source_snapshot.snapshot_json::JSONB->>'bindingJson' IS DISTINCT FROM source_commit.binding_json
        OR source_snapshot.snapshot_json::JSONB->>'bindingDigest' IS DISTINCT FROM source_commit.input_digest
        OR source_snapshot.snapshot_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(convert_to(source_snapshot.snapshot_json, 'UTF8')), 'hex')
        OR NOT EXISTS (
            SELECT 1 FROM game_design_draft_commit_visibility_fence fence
            WHERE fence.canonical_tenant_id = NEW.canonical_tenant_id
                AND fence.canonical_version_id = NEW.canonical_version_id
                AND fence.request_id = source_snapshot.request_id AND fence.commit_id = NEW.commit_id
                AND fence.input_digest = source_commit.input_digest)
        OR NOT EXISTS (
            SELECT 1 FROM game_design_draft_commit_visibility visible
            WHERE visible.canonical_tenant_id = NEW.canonical_tenant_id
                AND visible.canonical_version_id = NEW.canonical_version_id
                AND visible.request_id = source_snapshot.request_id AND visible.commit_id = NEW.commit_id
                AND visible.input_digest = source_commit.input_digest)
        OR source_head.visible_commit_id IS DISTINCT FROM NEW.commit_id
        OR source_head.source_epoch IS DISTINCT FROM source_snapshot.snapshot_json::JSONB->>'sourceEpoch'
        OR EXISTS (SELECT 1 FROM revision legacy
            WHERE legacy.tenant_id = owner_version.tenant_id AND legacy.version_id = owner_version.id
                AND legacy.revision_kind = 'GAMEPLAY_RULE')
        OR NOT EXISTS (
            SELECT 1 FROM game_design_command_source_snapshot command_snapshot
            WHERE command_snapshot.canonical_tenant_id = NEW.canonical_tenant_id
                AND command_snapshot.canonical_version_id = NEW.canonical_version_id
                AND command_snapshot.commit_id = NEW.commit_id
                AND command_snapshot.snapshot_json::JSONB->>'bindingJson' = source_commit.binding_json
                AND command_snapshot.snapshot_json::JSONB->>'bindingDigest' = source_commit.input_digest
                AND command_snapshot.snapshot_json::JSONB->'definitions' = '[]'::JSONB)
        OR expected IS DISTINCT FROM NEW.capture_bytes
        OR NEW.capture_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.capture_bytes), 'hex') THEN
        RAISE EXCEPTION 'gameplay-rule capture differs from exact selected operation and synchronized source'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gameplay_rule_source_capture_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_gameplay_rule_source_capture
    FOR EACH ROW EXECUTE FUNCTION guard_gameplay_rule_source_capture();

CREATE FUNCTION deny_gameplay_rule_source_capture_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'gameplay-rule publication source capture cannot be truncated' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gameplay_rule_source_capture_no_truncate
    BEFORE TRUNCATE ON game_design_gameplay_rule_source_capture
    FOR EACH STATEMENT EXECUTE FUNCTION deny_gameplay_rule_source_capture_truncate();
-- [jooq ignore stop]
