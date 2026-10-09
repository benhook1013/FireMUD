-- A new exact StartSession descriptor is inseparable from the original Account/GS execution and
-- the immutable selected template/source association that authorized its launch target.
CREATE TABLE game_design_start_session_launch_descriptor_binding (
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    control_plane_request_id VARCHAR(255) NOT NULL,
    launch_descriptor_row_id BIGINT NOT NULL UNIQUE
        REFERENCES launch_descriptor (id) ON DELETE RESTRICT,
    release_bundle_id BIGINT NOT NULL
        REFERENCES published_release_bundle (id) ON DELETE RESTRICT,
    version_state_epoch BIGINT NOT NULL,
    canonical_post_authorization_tuple BYTEA NOT NULL,
    owner_attempt_id UUID NOT NULL,
    owner_fence BIGINT NOT NULL,
    game_template_id BIGINT NOT NULL,
    canonical_version_id UUID NOT NULL,
    selected_commit_id UUID NOT NULL,
    publish_workflow_id VARCHAR(1024) NOT NULL,
    publication_selection_digest VARCHAR(71) NOT NULL,
    association_digest VARCHAR(71) NOT NULL,
    source_operation_id UUID NOT NULL
        REFERENCES game_design_authored_world_source_operations (operation_id) ON DELETE RESTRICT,
    source_evidence_digest VARCHAR(71) NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    reference_phase_epoch BIGINT NOT NULL,
    runtime_surface VARCHAR(32) NOT NULL,
    game_logic_publish_request_id VARCHAR(1024) NOT NULL,
    game_logic_selection_digest VARCHAR(71) NOT NULL,
    game_logic_authorization_digest VARCHAR(71) NOT NULL,
    game_logic_receipt_digest VARCHAR(71) NOT NULL,
    association_read_response_bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_start_session_launch_descriptor_binding PRIMARY KEY (
        target_namespace,
        canonical_tenant_id,
        control_plane_request_id
    ),
    CONSTRAINT fk_gd_start_session_launch_association FOREIGN KEY (
        canonical_tenant_id,
        canonical_version_id,
        selected_commit_id,
        game_template_id
    ) REFERENCES game_design_selected_template_world_source_association (
        canonical_tenant_id,
        canonical_version_id,
        commit_id,
        template_id
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_gd_start_session_launch_game_logic_receipt FOREIGN KEY (
        canonical_tenant_id,
        game_logic_publish_request_id
    ) REFERENCES game_design_selected_game_logic_receipt (
        canonical_tenant_id,
        publish_request_id
    ) ON DELETE RESTRICT,
    CONSTRAINT ck_gd_start_session_launch_binding_identity CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND control_plane_request_id !~ '^[[:space:]]*$'
        AND release_bundle_id > 0
        AND version_state_epoch > 0
        AND octet_length(canonical_post_authorization_tuple) BETWEEN 1 AND 262144
        AND owner_attempt_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND owner_fence > 0
        AND game_template_id > 0
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND selected_commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND char_length(publish_workflow_id) BETWEEN 1 AND 1024
        AND publication_selection_digest ~ '^sha256:[0-9a-f]{64}$'
        AND association_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        AND reference_phase_epoch > 0
        AND runtime_surface IN ('EMPTY_V1', 'UNSUPPORTED_CAPTURE_V1')
        AND char_length(game_logic_publish_request_id) BETWEEN 1 AND 1024
        AND game_logic_selection_digest ~ '^sha256:[0-9a-f]{64}$'
        AND game_logic_authorization_digest ~ '^sha256:[0-9a-f]{64}$'
        AND game_logic_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND octet_length(association_read_response_bytes) > 0
    )
);

-- [jooq ignore start]
CREATE FUNCTION guard_gd_start_session_launch_descriptor_binding() RETURNS trigger AS $$
DECLARE descriptor launch_descriptor%ROWTYPE;
    association game_design_selected_template_world_source_association%ROWTYPE;
    current_version version%ROWTYPE;
    current_phase game_template_reference_phase%ROWTYPE;
    publication game_design_publication_operation%ROWTYPE;
    owner_source game_design_authored_world_source_operations%ROWTYPE;
    release published_release_bundle%ROWTYPE;
    game_logic_receipt game_design_selected_game_logic_receipt%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'StartSession launch descriptor bindings are immutable and retained'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO STRICT descriptor
      FROM launch_descriptor
     WHERE id = NEW.launch_descriptor_row_id;
    SELECT * INTO STRICT association
      FROM game_design_selected_template_world_source_association
     WHERE canonical_tenant_id = NEW.canonical_tenant_id
       AND canonical_version_id = NEW.canonical_version_id
       AND commit_id = NEW.selected_commit_id
       AND template_id = NEW.game_template_id;
    SELECT * INTO STRICT current_version
      FROM version
     WHERE canonical_tenant_id = NEW.canonical_tenant_id
       AND canonical_version_id = NEW.canonical_version_id;
    SELECT * INTO STRICT current_phase
      FROM game_template_reference_phase
     WHERE canonical_tenant_id = NEW.canonical_tenant_id;
    SELECT * INTO STRICT publication
      FROM game_design_publication_operation
     WHERE publish_workflow_id = NEW.publish_workflow_id;
    SELECT * INTO STRICT owner_source
      FROM game_design_authored_world_source_operations
     WHERE operation_id = NEW.source_operation_id;
    SELECT * INTO STRICT release
      FROM published_release_bundle
     WHERE id = NEW.release_bundle_id;
    SELECT * INTO STRICT game_logic_receipt
      FROM game_design_selected_game_logic_receipt
     WHERE canonical_tenant_id = NEW.canonical_tenant_id
       AND publish_request_id = NEW.game_logic_publish_request_id;

    IF descriptor.outcome_status NOT IN ('SUCCESS', 'FAILED')
        OR descriptor.descriptor_schema_version <> 1
        OR descriptor.target_namespace IS DISTINCT FROM NEW.target_namespace
        OR descriptor.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR descriptor.control_plane_request_id IS DISTINCT FROM NEW.control_plane_request_id
        OR descriptor.tenant_id IS DISTINCT FROM owner_source.source_game_tenant_key
        OR current_version.version_state_epoch IS DISTINCT FROM NEW.version_state_epoch
        OR current_version.version_state NOT IN ('PUBLISHED', 'ACTIVE')
        OR current_version.is_script_only IS DISTINCT FROM FALSE
        OR current_version.script_patch_version IS NOT NULL
        OR current_phase.phase IS DISTINCT FROM 'ENFORCED'
        OR current_phase.phase_epoch IS DISTINCT FROM NEW.reference_phase_epoch
        OR publication.outcome IS DISTINCT FROM 'PUBLISHED'
        OR publication.selection_digest IS DISTINCT FROM NEW.publication_selection_digest
        OR owner_source.target_namespace IS DISTINCT FROM NEW.target_namespace
        OR owner_source.canonical_tenant_id IS DISTINCT FROM NEW.canonical_tenant_id
        OR owner_source.world_slug IS DISTINCT FROM NEW.world_slug
        OR owner_source.evidence_digest IS DISTINCT FROM NEW.source_evidence_digest
        OR owner_source.provenance_kind IS DISTINCT FROM 'NEW_GAME_ROW'
        OR owner_source.source_game_row_id IS DISTINCT FROM current_phase.source_game_row_id
        OR owner_source.source_game_tenant_key IS DISTINCT FROM current_phase.source_game_tenant_key
        OR release.tenant_id IS DISTINCT FROM current_version.tenant_id
        OR release.version_id IS DISTINCT FROM current_version.id
        OR release.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
        OR game_logic_receipt.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR game_logic_receipt.selection_digest IS DISTINCT FROM NEW.game_logic_selection_digest
        OR game_logic_receipt.selection_digest IS DISTINCT FROM NEW.publication_selection_digest
        OR game_logic_receipt.authorization_digest IS DISTINCT FROM NEW.game_logic_authorization_digest
        OR game_logic_receipt.receipt_digest IS DISTINCT FROM NEW.game_logic_receipt_digest
        OR game_logic_receipt.workflow_identity IS DISTINCT FROM NEW.publish_workflow_id
        OR descriptor.authored_world_source_operation_id IS DISTINCT FROM NEW.source_operation_id
        OR descriptor.authored_world_source_evidence_digest IS DISTINCT FROM NEW.source_evidence_digest
        OR descriptor.world_slug IS DISTINCT FROM NEW.world_slug
        OR association.publish_workflow_id IS DISTINCT FROM NEW.publish_workflow_id
        OR association.target_namespace IS DISTINCT FROM NEW.target_namespace
        OR association.canonical_version_id IS DISTINCT FROM NEW.canonical_version_id
        OR association.commit_id IS DISTINCT FROM NEW.selected_commit_id
        OR association.source_operation_id IS DISTINCT FROM NEW.source_operation_id
        OR association.source_evidence_digest IS DISTINCT FROM NEW.source_evidence_digest
        OR association.world_slug IS DISTINCT FROM NEW.world_slug
        OR association.association_digest IS DISTINCT FROM NEW.association_digest
        OR (descriptor.outcome_status = 'SUCCESS' AND (
            NEW.runtime_surface IS DISTINCT FROM 'EMPTY_V1'
            OR descriptor.game_template_id IS DISTINCT FROM NEW.game_template_id
            OR descriptor.version_id IS DISTINCT FROM current_version.id
            OR descriptor.version_state_epoch IS DISTINCT FROM NEW.version_state_epoch
            OR descriptor.release_bundle_id IS DISTINCT FROM NEW.release_bundle_id
            OR descriptor.published_release_bundle_ref IS DISTINCT FROM release.published_release_bundle_ref
            OR descriptor.generation_config_revision IS DISTINCT FROM release.generation_config_revision
            OR descriptor.failure_code IS NOT NULL
            OR descriptor.failure_message IS NOT NULL
            OR descriptor.result_digest IS NULL
        ))
        OR (descriptor.outcome_status = 'FAILED' AND (
            NEW.runtime_surface IS DISTINCT FROM 'UNSUPPORTED_CAPTURE_V1'
            OR descriptor.failure_code IS DISTINCT FROM 'INVALID_TEMPLATE_CONFIGURATION'
            OR descriptor.failure_message IS DISTINCT FROM
                'INVALID_TEMPLATE_CONFIGURATION: captured template has unsupported World, Entity, or Automation references'
            OR descriptor.game_template_id IS NOT NULL
            OR descriptor.version_id IS NOT NULL
            OR descriptor.script_patch_version IS NOT NULL
            OR descriptor.runtime_flags_json IS NOT NULL
            OR descriptor.generation_config_revision IS NOT NULL
            OR descriptor.version_state_epoch IS NOT NULL
            OR descriptor.release_bundle_id IS NOT NULL
            OR descriptor.published_release_bundle_ref IS NOT NULL
            OR descriptor.remap_set_id IS NOT NULL
            OR descriptor.result_digest IS NOT NULL
        )) THEN
        RAISE EXCEPTION 'StartSession launch descriptor binding differs from exact immutable owner evidence'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_start_session_launch_descriptor_binding
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_start_session_launch_descriptor_binding
    FOR EACH ROW EXECUTE FUNCTION guard_gd_start_session_launch_descriptor_binding();

CREATE FUNCTION reject_gd_start_session_launch_descriptor_binding_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'StartSession launch descriptor bindings are retained' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_start_session_launch_descriptor_binding_no_truncate
    BEFORE TRUNCATE ON game_design_start_session_launch_descriptor_binding
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gd_start_session_launch_descriptor_binding_truncate();
-- [jooq ignore stop]
