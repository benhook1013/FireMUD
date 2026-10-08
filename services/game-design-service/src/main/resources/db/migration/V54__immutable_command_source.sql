CREATE TABLE game_design_command_source_baseline (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    game_design_version_row_id BIGINT NOT NULL,
    baseline_kind VARCHAR(32) NOT NULL,
    receipt_id UUID NOT NULL,
    creation_transaction_id TEXT NOT NULL,
    target_proof_json TEXT NOT NULL,
    receipt_bytes BYTEA NOT NULL CHECK (octet_length(receipt_bytes) > 0),
    receipt_digest VARCHAR(71) NOT NULL CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    UNIQUE (receipt_id),
    UNIQUE (game_design_version_row_id),
    FOREIGN KEY (game_design_version_row_id, canonical_tenant_id, canonical_version_id)
        REFERENCES version (id, canonical_tenant_id, canonical_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (game_design_version_row_id)
        REFERENCES game_design_realm_policy_version_insert (version_id) ON DELETE RESTRICT,
    CHECK (baseline_kind = 'NEW_DRAFT_EMPTY'),
    CHECK (receipt_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK (creation_transaction_id ~ '^[1-9][0-9]*$')
);

CREATE TABLE game_design_command_source_snapshot (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    request_id UUID NOT NULL,
    input_digest VARCHAR(71) NOT NULL CHECK (input_digest ~ '^sha256:[0-9a-f]{64}$'),
    binding_json TEXT NOT NULL,
    inherited_commit_id UUID,
    baseline_digest VARCHAR(71) NOT NULL CHECK (baseline_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_epoch TEXT NOT NULL CHECK (source_epoch ~ '^(0|[1-9][0-9]*)$'),
    snapshot_json TEXT NOT NULL,
    snapshot_digest VARCHAR(71) NOT NULL CHECK (snapshot_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, request_id, commit_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit_visibility_fence
            (canonical_tenant_id, canonical_version_id, request_id, commit_id) ON DELETE RESTRICT,
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, inherited_commit_id)
        REFERENCES game_design_command_source_snapshot
            (canonical_tenant_id, canonical_version_id, commit_id) ON DELETE RESTRICT
);

CREATE TABLE game_design_command_source_application (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    request_id UUID NOT NULL,
    input_digest VARCHAR(71) NOT NULL CHECK (input_digest ~ '^sha256:[0-9a-f]{64}$'),
    binding_json TEXT NOT NULL,
    inherited_commit_id UUID,
    baseline_digest VARCHAR(71) NOT NULL CHECK (baseline_digest ~ '^sha256:[0-9a-f]{64}$'),
    expected_epoch TEXT NOT NULL CHECK (expected_epoch ~ '^(0|[1-9][0-9]*)$'),
    snapshot_json TEXT NOT NULL,
    operations_json TEXT NOT NULL,
    result_bytes BYTEA NOT NULL CHECK (octet_length(result_bytes) > 0),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id),
    UNIQUE (canonical_tenant_id, canonical_version_id, request_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, request_id, commit_id)
        REFERENCES game_design_draft_commit
            (canonical_tenant_id, canonical_version_id, request_id, commit_id) ON DELETE RESTRICT,
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, inherited_commit_id)
        REFERENCES game_design_command_source_snapshot
            (canonical_tenant_id, canonical_version_id, commit_id) ON DELETE RESTRICT
);

CREATE TABLE game_design_command_source_operation (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    revision_order INTEGER NOT NULL CHECK (revision_order >= 0),
    revision_id UUID NOT NULL,
    operation_kind VARCHAR(16) NOT NULL CHECK (operation_kind IN ('UPSERT', 'DELETE')),
    command_id TEXT NOT NULL CHECK (char_length(command_id) > 0),
    command_key TEXT NOT NULL CHECK (char_length(command_key) > 0),
    definition_json TEXT,
    operation_json TEXT NOT NULL,
    PRIMARY KEY (canonical_tenant_id, canonical_version_id, commit_id, revision_order),
    UNIQUE (canonical_tenant_id, canonical_version_id, revision_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, commit_id)
        REFERENCES game_design_command_source_application
            (canonical_tenant_id, canonical_version_id, commit_id) ON DELETE RESTRICT,
    CHECK (revision_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CHECK ((operation_kind = 'UPSERT' AND definition_json IS NOT NULL)
        OR (operation_kind = 'DELETE' AND definition_json IS NULL))
);

CREATE TABLE game_design_command_source_head (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    source_epoch TEXT NOT NULL CHECK (source_epoch ~ '^(0|[1-9][0-9]*)$'),
    visible_commit_id UUID,
    applied_commit_id UUID,
    baseline_digest VARCHAR(71) NOT NULL CHECK (baseline_digest ~ '^sha256:[0-9a-f]{64}$'),
    PRIMARY KEY (canonical_tenant_id, canonical_version_id),
    FOREIGN KEY (canonical_tenant_id, canonical_version_id)
        REFERENCES game_design_command_source_baseline (canonical_tenant_id, canonical_version_id) ON DELETE RESTRICT,
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, visible_commit_id)
        REFERENCES game_design_command_source_snapshot
            (canonical_tenant_id, canonical_version_id, commit_id) ON DELETE RESTRICT,
    FOREIGN KEY (canonical_tenant_id, canonical_version_id, applied_commit_id)
        REFERENCES game_design_command_source_application
            (canonical_tenant_id, canonical_version_id, commit_id) ON DELETE RESTRICT
);

CREATE TABLE game_design_command_source_capture (
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
        REFERENCES game_design_command_source_snapshot
            (canonical_tenant_id, canonical_version_id, commit_id) ON DELETE RESTRICT
);

-- [jooq ignore start]
ALTER TABLE game_design_command_source_application
    ADD CONSTRAINT command_source_application_json_shape CHECK (
        jsonb_typeof(snapshot_json::JSONB) = 'object'
        AND jsonb_typeof(operations_json::JSONB) = 'array'
        AND jsonb_array_length(operations_json::JSONB) > 0);
ALTER TABLE game_design_command_source_operation
    ADD CONSTRAINT command_source_operation_json_shape CHECK (
        jsonb_typeof(operation_json::JSONB) = 'object');

CREATE FUNCTION guard_command_source_write() RETURNS trigger AS $$
DECLARE owner_version version%ROWTYPE; binding game_design_draft_commit%ROWTYPE;
    witness game_design_realm_policy_version_insert%ROWTYPE; source_head game_design_command_source_head%ROWTYPE;
    owner_result game_design_draft_commit_owner_result%ROWTYPE; selection game_design_authored_draft_publish_selection%ROWTYPE;
    operation game_design_publication_operation%ROWTYPE; snapshot game_design_command_source_snapshot%ROWTYPE;
    expected BYTEA; payload BYTEA; receipt JSONB; proof JSONB; stored_operation JSONB; revision_payload JSONB;
    revision_payload_text TEXT; declared_command_count BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'command source evidence cannot be deleted' USING ERRCODE = 'check_violation';
    END IF;

    SELECT v.* INTO STRICT owner_version FROM version v JOIN game g
        ON g.id = v.identity_source_game_row_id AND g.tenant_id = v.identity_source_game_tenant_key
        AND g.canonical_tenant_id = v.canonical_tenant_id
        AND g.tenant_identity_provenance_kind = v.identity_source_provenance_kind
        AND g.tenant_identity_source_game_id = g.id
        AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
        WHERE v.canonical_tenant_id = NEW.canonical_tenant_id
            AND v.canonical_version_id = NEW.canonical_version_id FOR UPDATE OF v;
    IF owner_version.version_state <> 'DRAFT' OR owner_version.is_script_only
        OR owner_version.script_patch_version IS NOT NULL OR owner_version.base_version_id IS NOT NULL THEN
        RAISE EXCEPTION 'command source requires canonical full Draft owner' USING ERRCODE = 'check_violation';
    END IF;

    IF TG_TABLE_NAME = 'game_design_command_source_baseline' THEN
        IF TG_OP <> 'INSERT' THEN
            RAISE EXCEPTION 'command source baseline is immutable' USING ERRCODE = 'check_violation';
        END IF;
        SELECT * INTO STRICT witness FROM game_design_realm_policy_version_insert
            WHERE version_id = NEW.game_design_version_row_id;
        proof := NEW.target_proof_json::JSONB;
        receipt := convert_from(NEW.receipt_bytes, 'UTF8')::JSONB;
        IF NEW.baseline_kind <> 'NEW_DRAFT_EMPTY' OR owner_version.id <> NEW.game_design_version_row_id
            OR owner_version.version_state_epoch <> 1 OR witness.creation_transaction_id <> NEW.creation_transaction_id
            OR NEW.creation_transaction_id <> pg_current_xact_id()::TEXT
            OR NEW.receipt_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.receipt_bytes), 'hex')
            OR proof->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
            OR proof->>'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
            OR proof->>'gameDesignVersionRowId' IS DISTINCT FROM owner_version.id::TEXT
            OR proof->>'gameDesignVersionTenantKey' IS DISTINCT FROM owner_version.tenant_id
            OR proof->>'sourceGameRowId' IS DISTINCT FROM owner_version.identity_source_game_row_id::TEXT
            OR proof->>'sourceGameTenantKey' IS DISTINCT FROM owner_version.identity_source_game_tenant_key
            OR proof->>'sourceProvenanceKind' IS DISTINCT FROM owner_version.identity_source_provenance_kind
            OR jsonb_typeof(proof) IS DISTINCT FROM 'object'
            OR (SELECT count(*) FROM jsonb_object_keys(proof)) <> 7
            OR jsonb_typeof(receipt) IS DISTINCT FROM 'object'
            OR (SELECT count(*) FROM jsonb_object_keys(receipt)) <> 4
            OR receipt->>'schema' IS DISTINCT FROM 'game-design-command-source-new-draft-genesis/v1'
            OR receipt->'targetProof' IS DISTINCT FROM proof
            OR receipt->>'receiptId' IS DISTINCT FROM NEW.receipt_id::TEXT
            OR receipt->>'creationTransactionId' IS DISTINCT FROM NEW.creation_transaction_id THEN
            RAISE EXCEPTION 'command source baseline lacks exact fresh Version insertion evidence' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'game_design_command_source_head' THEN
        IF TG_OP = 'INSERT' THEN
            IF NEW.source_epoch <> '0' OR NEW.visible_commit_id IS NOT NULL OR NEW.applied_commit_id IS NOT NULL
                OR NOT EXISTS (SELECT 1 FROM game_design_command_source_baseline b
                    WHERE b.canonical_tenant_id = NEW.canonical_tenant_id
                        AND b.canonical_version_id = NEW.canonical_version_id
                        AND b.receipt_digest = NEW.baseline_digest) THEN
                RAISE EXCEPTION 'command source head must start at its exact empty Draft baseline' USING ERRCODE = 'check_violation';
            END IF;
            RETURN NEW;
        END IF;
        IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
            OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
            OR NEW.baseline_digest IS DISTINCT FROM OLD.baseline_digest
            OR EXISTS (SELECT 1 FROM game_design_command_source_capture c
                WHERE c.canonical_tenant_id = NEW.canonical_tenant_id AND c.canonical_version_id = NEW.canonical_version_id)
            OR EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection s
                WHERE s.canonical_tenant_id = NEW.canonical_tenant_id AND s.canonical_version_id = NEW.canonical_version_id) THEN
            RAISE EXCEPTION 'command source head is frozen or its identity changed' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.source_epoch IS DISTINCT FROM OLD.source_epoch THEN
            IF NEW.source_epoch::NUMERIC <> OLD.source_epoch::NUMERIC + 1
                OR NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id
                OR NEW.applied_commit_id IS NULL OR NEW.applied_commit_id IS NOT DISTINCT FROM OLD.applied_commit_id
                OR NOT EXISTS (SELECT 1 FROM game_design_command_source_application a
                    WHERE a.canonical_tenant_id = NEW.canonical_tenant_id
                        AND a.canonical_version_id = NEW.canonical_version_id
                        AND a.commit_id = NEW.applied_commit_id
                        AND a.expected_epoch = OLD.source_epoch) THEN
                RAISE EXCEPTION 'command source staged epoch must advance by its exact application' USING ERRCODE = 'check_violation';
            END IF;
        ELSIF NEW.applied_commit_id IS DISTINCT FROM OLD.applied_commit_id THEN
            RAISE EXCEPTION 'command source applied pointer changes only with an epoch advance' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.visible_commit_id IS DISTINCT FROM OLD.visible_commit_id THEN
            SELECT * INTO STRICT snapshot FROM game_design_command_source_snapshot
                WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                    AND commit_id = NEW.visible_commit_id;
            IF snapshot.inherited_commit_id IS DISTINCT FROM OLD.visible_commit_id
                OR snapshot.source_epoch IS DISTINCT FROM NEW.source_epoch
                OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_visibility_fence f
                    JOIN game_design_draft_commit_visibility v USING (canonical_tenant_id, canonical_version_id, request_id, commit_id)
                    JOIN game_design_draft_commit c USING (canonical_tenant_id, canonical_version_id, request_id, commit_id)
                    WHERE f.canonical_tenant_id = NEW.canonical_tenant_id AND f.canonical_version_id = NEW.canonical_version_id
                        AND f.request_id = snapshot.request_id AND f.commit_id = snapshot.commit_id
                        AND f.input_digest = snapshot.input_digest AND c.workflow_state = 'SYNCHRONIZED'
                        AND v.request_id = f.request_id AND v.commit_id = f.commit_id AND v.input_digest = f.input_digest) THEN
                RAISE EXCEPTION 'command source visibility must advance to the exact synchronized coordinator fence' USING ERRCODE = 'check_violation';
            END IF;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'command source history is immutable' USING ERRCODE = 'check_violation';
    END IF;

    IF TG_TABLE_NAME = 'game_design_command_source_application'
        OR TG_TABLE_NAME = 'game_design_command_source_snapshot' THEN
        SELECT * INTO STRICT binding FROM game_design_draft_commit
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
        IF binding.binding_json IS DISTINCT FROM NEW.binding_json OR binding.input_digest IS DISTINCT FROM NEW.input_digest THEN
            RAISE EXCEPTION 'command source differs from exact Draft binding' USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    IF TG_TABLE_NAME = 'game_design_command_source_application' THEN
        SELECT * INTO STRICT source_head FROM game_design_command_source_head
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id FOR UPDATE;
        IF source_head.source_epoch IS DISTINCT FROM NEW.expected_epoch
            OR source_head.visible_commit_id IS DISTINCT FROM NEW.inherited_commit_id
            OR source_head.baseline_digest IS DISTINCT FROM NEW.baseline_digest
            OR EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection s
                WHERE s.canonical_tenant_id = NEW.canonical_tenant_id AND s.canonical_version_id = NEW.canonical_version_id)
            OR NEW.snapshot_json::JSONB->>'schema' IS DISTINCT FROM 'game-design-command-source-snapshot/v1'
            OR NEW.snapshot_json::JSONB->>'bindingJson' IS DISTINCT FROM NEW.binding_json
            OR NEW.snapshot_json::JSONB->>'bindingDigest' IS DISTINCT FROM NEW.input_digest
            OR NEW.snapshot_json::JSONB->>'sourceEpoch' IS DISTINCT FROM (NEW.expected_epoch::NUMERIC + 1)::TEXT
            OR NEW.snapshot_json::JSONB->>'genesisReceiptDigest' IS DISTINCT FROM NEW.baseline_digest
            OR NEW.snapshot_json::JSONB->>'inheritedCommitId' IS DISTINCT FROM coalesce(NEW.inherited_commit_id::TEXT, '')
            OR jsonb_typeof(NEW.snapshot_json::JSONB->'definitions') IS DISTINCT FROM 'array'
            OR jsonb_typeof(NEW.operations_json::JSONB) IS DISTINCT FROM 'array'
            OR jsonb_array_length(NEW.operations_json::JSONB) = 0 THEN
            RAISE EXCEPTION 'command application must bind exact predecessor, baseline, epoch, and complete set' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'game_design_command_source_operation' THEN
        SELECT * INTO STRICT binding FROM game_design_draft_commit c
            WHERE c.canonical_tenant_id = NEW.canonical_tenant_id AND c.canonical_version_id = NEW.canonical_version_id
                AND c.commit_id = NEW.commit_id AND EXISTS (SELECT 1 FROM game_design_command_source_application a
                    WHERE a.canonical_tenant_id = c.canonical_tenant_id AND a.canonical_version_id = c.canonical_version_id
                        AND a.commit_id = c.commit_id);
        SELECT rev->>'payload' INTO STRICT revision_payload_text FROM jsonb_array_elements(binding.binding_json::JSONB->'revisions') rev
            WHERE rev->>'revisionOrder' = NEW.revision_order::TEXT AND rev->>'revisionId' = NEW.revision_id::TEXT
                AND rev->>'owner' = 'GAME_DESIGN_CONTROL_PLANE';
        revision_payload := revision_payload_text::JSONB;
        stored_operation := NEW.operation_json::JSONB;
        IF revision_payload->>'revisionKind' IS DISTINCT FROM 'COMMAND_DEFINITION'
            OR revision_payload->>'schemaVersion' IS DISTINCT FROM '1'
            OR (SELECT count(*) FROM jsonb_object_keys(revision_payload)) <> 4
            OR revision_payload->>'operation' IS DISTINCT FROM NEW.operation_kind
            OR (SELECT count(*) FROM jsonb_object_keys(stored_operation))
                <> CASE WHEN NEW.operation_kind = 'UPSERT' THEN 6 ELSE 5 END
            OR stored_operation->>'revisionOrder' IS DISTINCT FROM NEW.revision_order::TEXT
            OR stored_operation->>'revisionId' IS DISTINCT FROM NEW.revision_id::TEXT
            OR stored_operation->>'commitId' IS DISTINCT FROM NEW.commit_id::TEXT
            OR stored_operation->>'operation' IS DISTINCT FROM NEW.operation_kind
            OR stored_operation->>'commandId' IS DISTINCT FROM NEW.command_id THEN
            RAISE EXCEPTION 'command operation must retain its exact authored revision identity' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.operation_kind = 'UPSERT' THEN
            IF revision_payload->>'definitionJson' IS DISTINCT FROM NEW.definition_json
                OR revision_payload->>'definitionJson' IS DISTINCT FROM stored_operation->>'definitionJson'
                OR NEW.definition_json::JSONB->>'commandId' IS DISTINCT FROM NEW.command_id THEN
                RAISE EXCEPTION 'command upsert definition differs from digest-bound authored payload' USING ERRCODE = 'check_violation';
            END IF;
        ELSIF revision_payload->>'commandId' IS DISTINCT FROM NEW.command_id
            OR revision_payload ? 'definitionJson' OR stored_operation ? 'definitionJson' THEN
            RAISE EXCEPTION 'command delete must retain explicit identity without inferred absence' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'game_design_command_source_snapshot' THEN
        SELECT * INTO STRICT source_head FROM game_design_command_source_head
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id FOR UPDATE;
        SELECT count(*) INTO declared_command_count FROM jsonb_array_elements(binding.binding_json::JSONB->'revisions') revision
            WHERE CASE WHEN revision->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
                THEN (revision->>'payload')::JSONB->>'revisionKind' = 'COMMAND_DEFINITION' ELSE FALSE END;
        IF (declared_command_count > 0) IS DISTINCT FROM (EXISTS (SELECT 1 FROM game_design_command_source_application a
            WHERE a.canonical_tenant_id = NEW.canonical_tenant_id AND a.canonical_version_id = NEW.canonical_version_id
                AND a.commit_id = NEW.commit_id)) THEN
            RAISE EXCEPTION 'command-bearing synchronized commit requires its exact staged command application' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.snapshot_json::JSONB->>'schema' IS DISTINCT FROM 'game-design-command-source-snapshot/v1'
            OR NEW.snapshot_json::JSONB->>'bindingJson' IS DISTINCT FROM NEW.binding_json
            OR NEW.snapshot_json::JSONB->>'bindingDigest' IS DISTINCT FROM NEW.input_digest
            OR NEW.snapshot_json::JSONB->>'sourceEpoch' IS DISTINCT FROM NEW.source_epoch
            OR NEW.snapshot_json::JSONB->>'genesisReceiptDigest' IS DISTINCT FROM NEW.baseline_digest
            OR NEW.snapshot_json::JSONB->>'inheritedCommitId' IS DISTINCT FROM coalesce(NEW.inherited_commit_id::TEXT, '')
            OR jsonb_typeof(NEW.snapshot_json::JSONB->'definitions') IS DISTINCT FROM 'array'
            OR source_head.visible_commit_id IS DISTINCT FROM NEW.inherited_commit_id
            OR source_head.baseline_digest IS DISTINCT FROM NEW.baseline_digest
            OR source_head.source_epoch IS DISTINCT FROM NEW.source_epoch
            OR NEW.snapshot_digest IS DISTINCT FROM 'sha256:' || encode(sha256(convert_to(NEW.snapshot_json, 'UTF8')), 'hex')
            OR NOT EXISTS (SELECT 1 FROM game_design_draft_commit_visibility_fence f
                JOIN game_design_draft_commit_visibility v USING (canonical_tenant_id, canonical_version_id, request_id, commit_id)
                JOIN game_design_draft_commit c USING (canonical_tenant_id, canonical_version_id, request_id, commit_id)
                WHERE f.canonical_tenant_id = NEW.canonical_tenant_id AND f.canonical_version_id = NEW.canonical_version_id
                    AND f.request_id = NEW.request_id AND f.commit_id = NEW.commit_id
                    AND f.input_digest = NEW.input_digest AND v.request_id = f.request_id
                    AND v.commit_id = f.commit_id AND v.input_digest = f.input_digest AND c.workflow_state = 'SYNCHRONIZED') THEN
            RAISE EXCEPTION 'command snapshot must retain complete source at exact synchronized fence' USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (SELECT 1 FROM game_design_command_source_application a
            WHERE a.canonical_tenant_id = NEW.canonical_tenant_id AND a.canonical_version_id = NEW.canonical_version_id
                AND a.commit_id = NEW.commit_id AND a.snapshot_json IS DISTINCT FROM NEW.snapshot_json) THEN
            RAISE EXCEPTION 'command snapshot differs from exact staged application' USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM game_design_command_source_application a
            WHERE a.canonical_tenant_id = NEW.canonical_tenant_id AND a.canonical_version_id = NEW.canonical_version_id
                AND a.commit_id = NEW.commit_id)
            AND coalesce(NEW.snapshot_json::JSONB->'definitions', '[]'::JSONB)
                IS DISTINCT FROM coalesce((SELECT s.snapshot_json::JSONB->'definitions'
                    FROM game_design_command_source_snapshot s WHERE s.canonical_tenant_id = NEW.canonical_tenant_id
                        AND s.canonical_version_id = NEW.canonical_version_id AND s.commit_id = NEW.inherited_commit_id), '[]'::JSONB) THEN
            RAISE EXCEPTION 'disjoint synchronized commit must inherit exact complete command set' USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection s
            WHERE s.canonical_tenant_id = NEW.canonical_tenant_id AND s.canonical_version_id = NEW.canonical_version_id) THEN
            RAISE EXCEPTION 'command source is frozen by publication selection' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'game_design_command_source_capture' THEN
        SELECT * INTO STRICT operation FROM game_design_publication_operation
            WHERE publish_workflow_id = NEW.publish_workflow_id FOR UPDATE;
        SELECT * INTO STRICT selection FROM game_design_authored_draft_publish_selection
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
        SELECT * INTO STRICT snapshot FROM game_design_command_source_snapshot
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
                AND commit_id = NEW.commit_id;
        SELECT * INTO STRICT source_head FROM game_design_command_source_head
            WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
        IF operation.outcome <> 'PENDING' OR operation.request_bytes IS DISTINCT FROM NEW.operation_bytes
            OR operation.version_id <> owner_version.id OR operation.tenant_id <> owner_version.tenant_id
            OR operation.selection_digest <> selection.selection_digest OR selection.selected_commit_id <> NEW.commit_id
            OR source_head.visible_commit_id IS DISTINCT FROM NEW.commit_id
            OR source_head.source_epoch IS DISTINCT FROM snapshot.source_epoch
            OR NEW.capture_digest IS DISTINCT FROM 'sha256:' || encode(sha256(NEW.capture_bytes), 'hex') THEN
            RAISE EXCEPTION 'command capture must bind actual pending operation and exact selected synchronized source' USING ERRCODE = 'check_violation';
        END IF;
        payload := convert_to('game-design-command-source-capture/v1', 'UTF8');
        expected := int4send(octet_length(payload)) || payload;
        expected := expected || int4send(octet_length(NEW.operation_bytes)) || NEW.operation_bytes;
        payload := convert_to(snapshot.snapshot_json, 'UTF8');
        expected := expected || int4send(octet_length(payload)) || payload;
        IF expected IS DISTINCT FROM NEW.capture_bytes THEN
            RAISE EXCEPTION 'command capture bytes differ from immutable source and operation' USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION verify_command_source_application_commit() RETURNS trigger AS $$
DECLARE binding game_design_draft_commit%ROWTYPE; owner_result game_design_draft_commit_owner_result%ROWTYPE;
    source_head game_design_command_source_head%ROWTYPE;
    expected BYTEA; payload BYTEA; operation_count BIGINT; declared_count BIGINT; mutation_count BIGINT;
BEGIN
    SELECT * INTO STRICT binding FROM game_design_draft_commit WHERE canonical_tenant_id = NEW.canonical_tenant_id
        AND canonical_version_id = NEW.canonical_version_id AND request_id = NEW.request_id AND commit_id = NEW.commit_id;
    SELECT * INTO STRICT owner_result FROM game_design_draft_commit_owner_result
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
            AND request_id = NEW.request_id AND owner = 'GAME_DESIGN_CONTROL_PLANE';
    SELECT * INTO STRICT source_head FROM game_design_command_source_head
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id;
    SELECT count(*) INTO operation_count FROM game_design_command_source_operation
        WHERE canonical_tenant_id = NEW.canonical_tenant_id AND canonical_version_id = NEW.canonical_version_id
            AND commit_id = NEW.commit_id;
    SELECT jsonb_array_length(NEW.operations_json::JSONB) INTO declared_count;
    SELECT count(*) INTO mutation_count FROM jsonb_array_elements(binding.binding_json::JSONB->'revisions') revision
        WHERE CASE WHEN revision->>'owner' = 'GAME_DESIGN_CONTROL_PLANE'
            THEN (revision->>'payload')::JSONB->>'revisionKind' = 'COMMAND_DEFINITION' ELSE FALSE END;
    IF owner_result.status <> 'APPLIED' OR owner_result.result_commit_id <> NEW.commit_id
        OR owner_result.result_binding_digest <> binding.input_digest OR declared_count <> operation_count
        OR mutation_count <> operation_count
        OR source_head.source_epoch IS DISTINCT FROM (NEW.expected_epoch::NUMERIC + 1)::TEXT
        OR source_head.applied_commit_id IS DISTINCT FROM NEW.commit_id
        OR owner_result.applied_units_json IS NULL
        OR NOT owner_result.applied_units_json::JSONB @> jsonb_build_array(jsonb_build_object(
            'aggregateType', 'COMMAND_DEFINITION_SET', 'aggregateId', NEW.canonical_version_id::TEXT,
            'scopeType', 'COMMAND_DEFINITION_SET', 'scopeId', 'effective',
            'expectedEpoch', NEW.expected_epoch,
            'resultingEpoch', (NEW.expected_epoch::NUMERIC + 1)::TEXT)) THEN
        RAISE EXCEPTION 'command application requires exact combined control-plane APPLIED owner outcome' USING ERRCODE = 'check_violation';
    END IF;
    payload := convert_to('game-design-command-source-application/v1', 'UTF8');
    expected := int4send(octet_length(payload)) || payload;
    payload := convert_to(coalesce(NEW.inherited_commit_id::TEXT, ''), 'UTF8');
    expected := expected || int4send(octet_length(payload)) || payload;
    payload := convert_to(NEW.expected_epoch, 'UTF8');
    expected := expected || int4send(octet_length(payload)) || payload;
    payload := convert_to(NEW.operations_json, 'UTF8');
    expected := expected || int4send(octet_length(payload)) || payload;
    payload := convert_to(NEW.snapshot_json, 'UTF8');
    expected := expected || int4send(octet_length(payload)) || payload;
    IF expected IS DISTINCT FROM NEW.result_bytes THEN
        RAISE EXCEPTION 'command application result bytes do not encode exact mutation and effective snapshot' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER command_source_baseline_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_command_source_baseline
    FOR EACH ROW EXECUTE FUNCTION guard_command_source_write();
CREATE TRIGGER command_source_snapshot_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_command_source_snapshot
    FOR EACH ROW EXECUTE FUNCTION guard_command_source_write();
CREATE TRIGGER command_source_application_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_command_source_application
    FOR EACH ROW EXECUTE FUNCTION guard_command_source_write();
CREATE TRIGGER command_source_operation_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_command_source_operation
    FOR EACH ROW EXECUTE FUNCTION guard_command_source_write();
CREATE TRIGGER command_source_head_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_command_source_head
    FOR EACH ROW EXECUTE FUNCTION guard_command_source_write();
CREATE TRIGGER command_source_capture_guard BEFORE INSERT OR UPDATE OR DELETE ON game_design_command_source_capture
    FOR EACH ROW EXECUTE FUNCTION guard_command_source_write();
CREATE CONSTRAINT TRIGGER command_source_application_commit_guard AFTER INSERT ON game_design_command_source_application
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_command_source_application_commit();

CREATE FUNCTION deny_command_source_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'command source evidence cannot be truncated' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER command_source_baseline_no_truncate BEFORE TRUNCATE ON game_design_command_source_baseline
    FOR EACH STATEMENT EXECUTE FUNCTION deny_command_source_truncate();
CREATE TRIGGER command_source_snapshot_no_truncate BEFORE TRUNCATE ON game_design_command_source_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION deny_command_source_truncate();
CREATE TRIGGER command_source_application_no_truncate BEFORE TRUNCATE ON game_design_command_source_application
    FOR EACH STATEMENT EXECUTE FUNCTION deny_command_source_truncate();
CREATE TRIGGER command_source_operation_no_truncate BEFORE TRUNCATE ON game_design_command_source_operation
    FOR EACH STATEMENT EXECUTE FUNCTION deny_command_source_truncate();
CREATE TRIGGER command_source_head_no_truncate BEFORE TRUNCATE ON game_design_command_source_head
    FOR EACH STATEMENT EXECUTE FUNCTION deny_command_source_truncate();
CREATE TRIGGER command_source_capture_no_truncate BEFORE TRUNCATE ON game_design_command_source_capture
    FOR EACH STATEMENT EXECUTE FUNCTION deny_command_source_truncate();
-- [jooq ignore stop]
