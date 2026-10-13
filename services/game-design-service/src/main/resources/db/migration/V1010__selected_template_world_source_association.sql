-- One immutable World-source relation for every template actually present in the selected capture.
CREATE TABLE game_design_selected_template_world_source_association (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    template_id BIGINT NOT NULL REFERENCES game_templates (id) ON DELETE RESTRICT,
    publish_workflow_id VARCHAR(1024) NOT NULL
        REFERENCES game_design_publication_operation (publish_workflow_id) ON DELETE RESTRICT,
    target_namespace VARCHAR(63) NOT NULL,
    intake_request_id UUID NOT NULL,
    world_operation_id UUID NOT NULL,
    source_operation_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL,
    operation_bytes BYTEA NOT NULL,
    capture_bytes BYTEA NOT NULL,
    template_snapshot_bytes BYTEA NOT NULL,
    template_entry_bytes BYTEA NOT NULL,
    world_read_request_bytes BYTEA NOT NULL,
    world_read_response_bytes BYTEA NOT NULL,
    association_bytes BYTEA NOT NULL,
    association_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id, template_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_template_config_source_snapshot
        (canonical_tenant_id, canonical_version_id, commit_id) ON DELETE RESTRICT,
    FOREIGN KEY (canonical_tenant_id, canonical_version_id)
        REFERENCES game_design_template_config_source_capture
        (canonical_tenant_id, canonical_version_id) ON DELETE RESTRICT,
    CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND template_id > 0
        AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND world_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CHECK (source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND association_digest ~ '^sha256:[0-9a-f]{64}$'),
    CHECK (octet_length(operation_bytes) > 0
        AND octet_length(capture_bytes) > 0
        AND octet_length(template_snapshot_bytes) > 0
        AND octet_length(template_entry_bytes) > 0
        AND octet_length(world_read_request_bytes) > 0
        AND octet_length(world_read_response_bytes) > 0
        AND octet_length(association_bytes) > 0)
);

-- [jooq ignore start]
CREATE FUNCTION enforce_gd_selected_template_world_source_association() RETURNS trigger AS $$
DECLARE
    op RECORD;
    capture RECORD;
    snapshot RECORD;
    matching_entry BOOLEAN;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Selected template/world source associations are immutable and retained'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO STRICT op
      FROM game_design_publication_operation
     WHERE publish_workflow_id = NEW.publish_workflow_id
     FOR UPDATE;
    SELECT * INTO STRICT capture
      FROM game_design_template_config_source_capture
     WHERE canonical_tenant_id = NEW.canonical_tenant_id
       AND canonical_version_id = NEW.canonical_version_id;
    SELECT * INTO STRICT snapshot
      FROM game_design_template_config_source_snapshot
     WHERE canonical_tenant_id = NEW.canonical_tenant_id
       AND canonical_version_id = NEW.canonical_version_id
       AND commit_id = NEW.commit_id;

    SELECT EXISTS (
        SELECT 1
          FROM jsonb_array_elements(snapshot.snapshot_json::JSONB -> 'entries') entry
         WHERE entry ->> 'templateId' = NEW.template_id::TEXT
           AND entry = convert_from(NEW.template_entry_bytes, 'UTF8')::JSONB
    ) INTO matching_entry;

    IF op.outcome <> 'PENDING'
        OR op.request_bytes IS DISTINCT FROM NEW.operation_bytes
        OR capture.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
        OR capture.commit_id IS DISTINCT FROM NEW.commit_id
        OR capture.operation_bytes IS DISTINCT FROM NEW.operation_bytes
        OR capture.capture_bytes IS DISTINCT FROM NEW.capture_bytes
        OR convert_to(snapshot.snapshot_json, 'UTF8') IS DISTINCT FROM NEW.template_snapshot_bytes
        OR NOT matching_entry
        OR NEW.association_digest IS DISTINCT FROM
            'sha256:' || encode(sha256(NEW.association_bytes), 'hex') THEN
        RAISE EXCEPTION 'Selected template/world source association differs from its exact pending capture'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_selected_template_world_source_association
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_selected_template_world_source_association
    FOR EACH ROW EXECUTE FUNCTION enforce_gd_selected_template_world_source_association();
-- [jooq ignore stop]
