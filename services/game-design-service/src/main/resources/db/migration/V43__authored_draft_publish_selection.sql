CREATE TABLE game_design_authored_draft_publish_selection (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    game_design_version_row_id BIGINT NOT NULL,
    game_design_version_tenant_key VARCHAR(36) NOT NULL,
    source_game_row_id BIGINT NOT NULL,
    source_game_tenant_key VARCHAR(36) NOT NULL,
    source_provenance_kind VARCHAR(32) NOT NULL,
    publish_request_id VARCHAR(1024) NOT NULL,
    version_state_epoch BIGINT NOT NULL,
    selected_commit_request_id UUID NOT NULL,
    selected_commit_id UUID NOT NULL,
    selected_commit_digest VARCHAR(71) NOT NULL,
    selection_digest VARCHAR(71) NOT NULL,
    selection_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_authored_draft_publish_selection PRIMARY KEY (
        canonical_tenant_id,
        canonical_version_id
    ),
    CONSTRAINT uq_gd_authored_draft_publish_selection_request UNIQUE (
        canonical_tenant_id,
        canonical_version_id,
        publish_request_id
    ),
    CONSTRAINT fk_gd_authored_draft_publish_selection_version FOREIGN KEY (
        game_design_version_row_id,
        canonical_tenant_id,
        canonical_version_id
    ) REFERENCES version (id, canonical_tenant_id, canonical_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_gd_authored_draft_publish_selection_source_game FOREIGN KEY (
        source_game_tenant_key,
        source_game_row_id
    ) REFERENCES game (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_gd_authored_draft_publish_selection_commit FOREIGN KEY (
        canonical_tenant_id,
        canonical_version_id,
        selected_commit_request_id,
        selected_commit_id
    ) REFERENCES game_design_draft_commit (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_gd_authored_draft_publish_selection_fence FOREIGN KEY (
        canonical_tenant_id,
        canonical_version_id,
        selected_commit_request_id,
        selected_commit_id
    ) REFERENCES game_design_draft_commit_visibility_fence (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ) ON DELETE RESTRICT,
    CONSTRAINT chk_gd_authored_draft_publish_selection_identity CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND game_design_version_row_id > 0
        AND source_game_row_id > 0
        AND game_design_version_tenant_key = source_game_tenant_key
        AND char_length(game_design_version_tenant_key) BETWEEN 1 AND 36
        AND game_design_version_tenant_key !~ '^[[:space:]]*$'
        AND source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V30')
        AND char_length(publish_request_id) BETWEEN 1 AND 1024
        AND publish_request_id !~ '^[[:space:]]*$'
        AND publish_request_id !~ ':'
        AND version_state_epoch > 0
        AND selected_commit_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND selected_commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gd_authored_draft_publish_selection_digests CHECK (
        selected_commit_digest ~ '^sha256:[0-9a-f]{64}$'
        AND selection_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_gd_authored_draft_publish_selection_json CHECK (
        jsonb_typeof(selection_json::jsonb) = 'object'
    )
);

-- [jooq ignore start]
CREATE FUNCTION enforce_gd_authored_draft_publish_selection() RETURNS trigger AS $$
DECLARE
    version_record RECORD;
    commit_record RECORD;
    current_fence RECORD;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Authored Draft publication selections are immutable and retained'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.selection_json::jsonb ->> 'schemaVersion' IS DISTINCT FROM '1'
        OR NEW.selection_json::jsonb -> 'intent' ->> 'canonicalTenantId'
            IS DISTINCT FROM NEW.canonical_tenant_id::text
        OR NEW.selection_json::jsonb -> 'intent' ->> 'canonicalVersionId'
            IS DISTINCT FROM NEW.canonical_version_id::text
        OR NEW.selection_json::jsonb -> 'intent' ->> 'publishRequestId'
            IS DISTINCT FROM NEW.publish_request_id
        OR NEW.selection_json::jsonb -> 'intent' ->> 'expectedVersionStateEpoch'
            IS DISTINCT FROM NEW.version_state_epoch::text
        OR NEW.selection_json::jsonb -> 'intent' ->> 'selectedCommitRequestId'
            IS DISTINCT FROM NEW.selected_commit_request_id::text
        OR NEW.selection_json::jsonb -> 'intent' ->> 'selectedCommitId'
            IS DISTINCT FROM NEW.selected_commit_id::text
        OR NEW.selection_json::jsonb -> 'intent' ->> 'selectedCommitDigest'
            IS DISTINCT FROM NEW.selected_commit_digest THEN
        RAISE EXCEPTION 'Stored authored Draft selection differs from its complete caller intent'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.selection_json::jsonb -> 'target' ->> 'canonicalTenantId'
            IS DISTINCT FROM NEW.canonical_tenant_id::text
        OR NEW.selection_json::jsonb -> 'target' ->> 'canonicalVersionId'
            IS DISTINCT FROM NEW.canonical_version_id::text
        OR NEW.selection_json::jsonb -> 'target' ->> 'gameDesignVersionRowId'
            IS DISTINCT FROM NEW.game_design_version_row_id::text
        OR NEW.selection_json::jsonb -> 'target' ->> 'gameDesignVersionTenantKey'
            IS DISTINCT FROM NEW.game_design_version_tenant_key
        OR NEW.selection_json::jsonb -> 'target' ->> 'sourceGameRowId'
            IS DISTINCT FROM NEW.source_game_row_id::text
        OR NEW.selection_json::jsonb -> 'target' ->> 'sourceGameTenantKey'
            IS DISTINCT FROM NEW.source_game_tenant_key
        OR NEW.selection_json::jsonb -> 'target' ->> 'sourceProvenanceKind'
            IS DISTINCT FROM NEW.source_provenance_kind THEN
        RAISE EXCEPTION 'Stored authored Draft selection differs from its private Version source proof'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT v.version_state, v.version_state_epoch, v.is_script_only,
           v.script_patch_version, v.base_version_id
      INTO version_record
    FROM version v
    JOIN game g
      ON g.id = v.identity_source_game_row_id
     AND g.tenant_id = v.identity_source_game_tenant_key
     AND g.canonical_tenant_id = v.canonical_tenant_id
     AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind
     AND g.tenant_identity_source_game_id = g.id
     AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
    WHERE v.id = NEW.game_design_version_row_id
      AND v.tenant_id = NEW.game_design_version_tenant_key
      AND v.canonical_tenant_id = NEW.canonical_tenant_id
      AND v.canonical_version_id = NEW.canonical_version_id
      AND v.identity_source_game_row_id = NEW.source_game_row_id
      AND v.identity_source_game_tenant_key = NEW.source_game_tenant_key
      AND v.identity_source_provenance_kind = NEW.source_provenance_kind
      AND v.version_state = 'DRAFT'
      AND v.version_state_epoch = NEW.version_state_epoch
      AND v.version_state_epoch > 0
      AND v.is_script_only = FALSE
      AND v.script_patch_version IS NULL
      AND v.base_version_id IS NULL
    FOR UPDATE OF v;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Authored Draft publication selection lacks an exact current full Draft Version source'
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF EXISTS (
        SELECT 1 FROM game_design_draft_commit_application_slot s
        WHERE s.canonical_tenant_id = NEW.canonical_tenant_id
          AND s.canonical_version_id = NEW.canonical_version_id
    ) THEN
        RAISE EXCEPTION 'Active or unresolved owner application prevents Draft publication selection'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT c.commit_id, c.input_digest, c.binding_json, c.workflow_state
      INTO commit_record
    FROM game_design_draft_commit c
    WHERE c.canonical_tenant_id = NEW.canonical_tenant_id
      AND c.canonical_version_id = NEW.canonical_version_id
      AND c.request_id = NEW.selected_commit_request_id
      AND c.commit_id = NEW.selected_commit_id;
    IF NOT FOUND
        OR commit_record.workflow_state <> 'SYNCHRONIZED'
        OR commit_record.input_digest <> NEW.selected_commit_digest
        OR NEW.selection_json::jsonb ->> 'selectedCommitDigest'
            IS DISTINCT FROM commit_record.input_digest
        OR NEW.selection_json::jsonb ->> 'selectedCommitBindingJson'
            IS DISTINCT FROM commit_record.binding_json THEN
        RAISE EXCEPTION 'Selected authored Draft commit is missing or differs from exact durable binding'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.selection_json::jsonb -> 'synchronizedFence' ->> 'requestId'
            IS DISTINCT FROM NEW.selected_commit_request_id::text
        OR NEW.selection_json::jsonb -> 'synchronizedFence' ->> 'commitId'
            IS DISTINCT FROM NEW.selected_commit_id::text
        OR NEW.selection_json::jsonb -> 'synchronizedFence' ->> 'inputDigest'
            IS DISTINCT FROM NEW.selected_commit_digest THEN
        RAISE EXCEPTION 'Stored authored Draft selection differs from exact synchronized fence identity'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT f.input_digest, f.result_vector_json
      INTO current_fence
    FROM game_design_draft_commit_visibility_fence f
    WHERE f.canonical_tenant_id = NEW.canonical_tenant_id
      AND f.canonical_version_id = NEW.canonical_version_id
      AND f.request_id = NEW.selected_commit_request_id
      AND f.commit_id = NEW.selected_commit_id;
    IF NOT FOUND
        OR current_fence.input_digest <> NEW.selected_commit_digest
        OR NEW.selection_json::jsonb -> 'synchronizedFence' ->> 'resultVectorJson'
            IS DISTINCT FROM current_fence.result_vector_json THEN
        RAISE EXCEPTION 'Stored authored Draft selection differs from the exact immutable result fence'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM game_design_draft_commit_visibility v
        WHERE v.canonical_tenant_id = NEW.canonical_tenant_id
          AND v.canonical_version_id = NEW.canonical_version_id
          AND v.request_id = NEW.selected_commit_request_id
          AND v.commit_id = NEW.selected_commit_id
          AND v.input_digest = NEW.selected_commit_digest
    ) THEN
        RAISE EXCEPTION 'Selected authored Draft commit is stale relative to current synchronized visibility'
            USING ERRCODE = 'check_violation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(commit_record.binding_json::jsonb -> 'requiredOwners') required(owner)
        LEFT JOIN game_design_draft_commit_owner_result outcome
          ON outcome.canonical_tenant_id = NEW.canonical_tenant_id
         AND outcome.canonical_version_id = NEW.canonical_version_id
         AND outcome.request_id = NEW.selected_commit_request_id
         AND outcome.owner = required.owner
        WHERE outcome.status IS DISTINCT FROM 'APPLIED'
    ) THEN
        RAISE EXCEPTION 'Selected authored Draft commit lacks every required APPLIED owner outcome'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_authored_draft_publish_selection
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_authored_draft_publish_selection
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_authored_draft_publish_selection();

CREATE FUNCTION reject_gd_draft_application_during_authored_publication() RETURNS trigger AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM game_design_authored_draft_publish_selection s
        WHERE s.canonical_tenant_id = NEW.canonical_tenant_id
          AND s.canonical_version_id = NEW.canonical_version_id
    ) THEN
        RAISE EXCEPTION 'Authored Draft publication reservation prevents later owner application'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_application_authored_publication_interlock
    BEFORE INSERT ON game_design_draft_commit_application_slot
    FOR EACH ROW
    EXECUTE FUNCTION reject_gd_draft_application_during_authored_publication();
-- [jooq ignore stop]
