-- Account-bound Game Design terminal readback is retained independently of coordinator workflow
-- status. Existing V42 rows are deliberately not backfilled: status-only history is not a receipt.
CREATE TABLE game_design_draft_terminal_operation (
    operation_id UUID NOT NULL,
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    fence_id UUID NOT NULL,
    actor_account_id UUID NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    base_commit_id VARCHAR(256) NOT NULL,
    expected_draft_epoch TEXT NOT NULL,
    account_binding_bytes BYTEA NOT NULL,
    account_binding_digest VARCHAR(71) NOT NULL,
    game_design_binding_json TEXT NOT NULL,
    game_design_input_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_draft_terminal_operation PRIMARY KEY (operation_id),
    CONSTRAINT uq_gd_draft_terminal_operation_commit UNIQUE (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ),
    CONSTRAINT uq_gd_draft_terminal_operation_fence UNIQUE (fence_id),
    CONSTRAINT fk_gd_draft_terminal_operation_commit FOREIGN KEY (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ) REFERENCES game_design_draft_commit (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ) ON DELETE RESTRICT,
    CONSTRAINT chk_gd_draft_terminal_operation_ids CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND fence_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND actor_account_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND octet_length(base_commit_id) BETWEEN 1 AND 256
        AND base_commit_id !~ '^[[:space:]]*$'
        AND expected_draft_epoch ~ '^(0|[1-9][0-9]*)$'
        AND octet_length(account_binding_bytes) > 0
    ),
    CONSTRAINT chk_gd_draft_terminal_operation_digests CHECK (
        account_binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND game_design_input_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_gd_draft_terminal_operation_binding CHECK (
        jsonb_typeof(game_design_binding_json::jsonb) = 'object'
    )
);

CREATE TABLE game_design_draft_terminal_outcome (
    operation_id UUID NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    owner_result_vector_json TEXT NOT NULL,
    terminal_evidence_bytes BYTEA NOT NULL,
    terminal_evidence_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_draft_terminal_outcome PRIMARY KEY (operation_id),
    CONSTRAINT fk_gd_draft_terminal_outcome_operation FOREIGN KEY (operation_id)
        REFERENCES game_design_draft_terminal_operation (operation_id) ON DELETE RESTRICT,
    CONSTRAINT chk_gd_draft_terminal_outcome_kind CHECK (
        outcome IN ('COMMITTED', 'DEFINITIVELY_ABORTED')
    ),
    CONSTRAINT chk_gd_draft_terminal_outcome_evidence CHECK (
        octet_length(terminal_evidence_bytes) > 0
        AND terminal_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND jsonb_typeof(owner_result_vector_json::jsonb) = 'array'
    )
);

-- [jooq ignore start]
CREATE FUNCTION gd_draft_terminal_vector_matches_owner_results(
    target_tenant UUID,
    target_version UUID,
    target_request UUID,
    target_commit UUID,
    vector_json TEXT
) RETURNS BOOLEAN AS $$
DECLARE
    parent_record RECORD;
    expected_count INTEGER;
BEGIN
    SELECT input_digest, binding_json
      INTO parent_record
    FROM game_design_draft_commit
    WHERE canonical_tenant_id = target_tenant
      AND canonical_version_id = target_version
      AND request_id = target_request
      AND commit_id = target_commit;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;
    IF jsonb_typeof(vector_json::jsonb) IS DISTINCT FROM 'array'
        OR jsonb_typeof(parent_record.binding_json::jsonb -> 'requiredOwners') IS DISTINCT FROM 'array' THEN
        RETURN FALSE;
    END IF;

    expected_count := jsonb_array_length(parent_record.binding_json::jsonb -> 'requiredOwners');
    IF jsonb_array_length(vector_json::jsonb) <> expected_count THEN
        RETURN FALSE;
    END IF;

    RETURN NOT EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(parent_record.binding_json::jsonb -> 'requiredOwners') required(owner)
        WHERE NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements(vector_json::jsonb) actual
            JOIN game_design_draft_commit_owner_result stored
              ON stored.canonical_tenant_id = target_tenant
             AND stored.canonical_version_id = target_version
             AND stored.request_id = target_request
             AND stored.owner = required.owner
            WHERE actual ->> 'owner' = required.owner
              AND actual ->> 'status' = stored.status
              AND actual ->> 'commitId' = stored.result_commit_id::text
              AND actual ->> 'bindingDigest' = stored.result_binding_digest
              AND actual ->> 'resultIdentity' = stored.result_identity
              AND actual ->> 'resultBytesBase64' = replace(encode(stored.result_bytes, 'base64'), E'\n', '')
              AND actual -> 'appliedEpochs' = stored.applied_units_json::jsonb
        )
    );
END;
$$ LANGUAGE plpgsql STABLE;

CREATE FUNCTION enforce_gd_draft_terminal_operation() RETURNS trigger AS $$
DECLARE
    parent_record RECORD;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Game Design Account operation bindings are immutable and retained'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT input_digest, binding_json, workflow_state
      INTO parent_record
    FROM game_design_draft_commit
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
      AND canonical_version_id = NEW.canonical_version_id
      AND request_id = NEW.request_id
      AND commit_id = NEW.commit_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account operation must claim an existing Game Design commit'
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF parent_record.input_digest IS DISTINCT FROM NEW.game_design_input_digest
        OR parent_record.binding_json IS DISTINCT FROM NEW.game_design_binding_json
        OR parent_record.workflow_state IS DISTINCT FROM 'QUEUED'
        OR NEW.game_design_binding_json::jsonb ->> 'canonicalTenantId'
            IS DISTINCT FROM NEW.canonical_tenant_id::text
        OR NEW.game_design_binding_json::jsonb ->> 'canonicalVersionId'
            IS DISTINCT FROM NEW.canonical_version_id::text
        OR NEW.game_design_binding_json::jsonb ->> 'requestId' IS DISTINCT FROM NEW.request_id::text
        OR NEW.game_design_binding_json::jsonb ->> 'commitId' IS DISTINCT FROM NEW.commit_id::text
        OR NEW.game_design_binding_json::jsonb ->> 'baseCommitId' IS DISTINCT FROM NEW.base_commit_id THEN
        RAISE EXCEPTION 'Account operation must claim the exact queued Game Design binding before dispatch'
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM game_design_draft_commit_application_slot slot
        WHERE slot.canonical_tenant_id = NEW.canonical_tenant_id
          AND slot.canonical_version_id = NEW.canonical_version_id
    ) OR EXISTS (
        SELECT 1
        FROM game_design_draft_commit_owner_result owner_result
        WHERE owner_result.canonical_tenant_id = NEW.canonical_tenant_id
          AND owner_result.canonical_version_id = NEW.canonical_version_id
          AND owner_result.request_id = NEW.request_id
          AND owner_result.status <> 'NOT_ATTEMPTED'
    ) THEN
        RAISE EXCEPTION 'Account operation binding was not retained before owner dispatch'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM version v
        WHERE v.canonical_tenant_id = NEW.canonical_tenant_id
          AND v.canonical_version_id = NEW.canonical_version_id
          AND v.version_state = 'DRAFT'
    ) THEN
        RAISE EXCEPTION 'Account operation can be claimed only for the exact DRAFT Version'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_terminal_operation
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_draft_terminal_operation
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_terminal_operation();

CREATE FUNCTION reject_gd_draft_terminal_operation_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM game_design_draft_terminal_operation) THEN
        RAISE EXCEPTION 'Game Design Account operation bindings cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_terminal_operation_no_truncate
    BEFORE TRUNCATE ON game_design_draft_terminal_operation
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_gd_draft_terminal_operation_truncate();

CREATE FUNCTION enforce_gd_draft_terminal_outcome() RETURNS trigger AS $$
DECLARE
    operation_record RECORD;
    commit_record RECORD;
    fence_record RECORD;
    abort_record RECORD;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Game Design terminal outcomes are immutable and retained'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT *
      INTO operation_record
    FROM game_design_draft_terminal_operation
    WHERE operation_id = NEW.operation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Game Design terminal outcome lacks its exact Account operation binding'
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    SELECT workflow_state, input_digest, binding_json
      INTO commit_record
    FROM game_design_draft_commit
    WHERE canonical_tenant_id = operation_record.canonical_tenant_id
      AND canonical_version_id = operation_record.canonical_version_id
      AND request_id = operation_record.request_id
      AND commit_id = operation_record.commit_id
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Game Design terminal outcome lacks its exact coordinator commit'
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF NOT gd_draft_terminal_vector_matches_owner_results(
            operation_record.canonical_tenant_id,
            operation_record.canonical_version_id,
            operation_record.request_id,
            operation_record.commit_id,
            NEW.owner_result_vector_json
        ) THEN
        RAISE EXCEPTION 'Terminal outcome requires the complete exact retained owner result vector'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.outcome = 'COMMITTED' THEN
        SELECT input_digest, result_vector_json
          INTO fence_record
        FROM game_design_draft_commit_visibility_fence
        WHERE canonical_tenant_id = operation_record.canonical_tenant_id
          AND canonical_version_id = operation_record.canonical_version_id
          AND request_id = operation_record.request_id
          AND commit_id = operation_record.commit_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Committed terminal outcome lacks its exact visibility fence'
                USING ERRCODE = 'check_violation';
        END IF;
        IF commit_record.workflow_state IS DISTINCT FROM 'SYNCHRONIZED'
            OR fence_record.input_digest IS DISTINCT FROM operation_record.game_design_input_digest
            OR fence_record.result_vector_json IS DISTINCT FROM NEW.owner_result_vector_json
            OR NEW.terminal_evidence_bytes IS DISTINCT FROM convert_to(NEW.owner_result_vector_json, 'UTF8')
            OR EXISTS (
                SELECT 1
                FROM jsonb_array_elements(NEW.owner_result_vector_json::jsonb) result_item
                WHERE result_item ->> 'status' IS DISTINCT FROM 'APPLIED'
            ) THEN
            RAISE EXCEPTION 'Committed terminal outcome must match synchronized visibility and every APPLIED owner'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.outcome = 'DEFINITIVELY_ABORTED' THEN
        SELECT abort_bytes
          INTO abort_record
        FROM game_design_draft_commit_final_abort
        WHERE canonical_tenant_id = operation_record.canonical_tenant_id
          AND canonical_version_id = operation_record.canonical_version_id
          AND request_id = operation_record.request_id
          AND commit_id = operation_record.commit_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Aborted terminal outcome lacks its exact final-abort tombstone'
                USING ERRCODE = 'check_violation';
        END IF;
        IF commit_record.workflow_state IS DISTINCT FROM 'FAILED_NONPUBLICATION'
            OR abort_record.abort_bytes IS DISTINCT FROM NEW.terminal_evidence_bytes
            OR EXISTS (
                SELECT 1
                FROM game_design_draft_commit_visibility_fence fence
                WHERE fence.canonical_tenant_id = operation_record.canonical_tenant_id
                  AND fence.canonical_version_id = operation_record.canonical_version_id
                  AND fence.request_id = operation_record.request_id
                  AND fence.commit_id = operation_record.commit_id
            )
            OR EXISTS (
                SELECT 1
                FROM jsonb_array_elements(NEW.owner_result_vector_json::jsonb) result_item
                WHERE result_item ->> 'status' NOT IN ('APPLIED', 'REJECTED')
            ) THEN
            RAISE EXCEPTION 'Aborted terminal outcome must match exact final-abort evidence and definitive participants'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unknown Game Design terminal outcome'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_terminal_outcome
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_draft_terminal_outcome
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_terminal_outcome();

CREATE FUNCTION reject_gd_draft_terminal_outcome_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM game_design_draft_terminal_outcome) THEN
        RAISE EXCEPTION 'Game Design terminal outcomes cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_terminal_outcome_no_truncate
    BEFORE TRUNCATE ON game_design_draft_terminal_outcome
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_gd_draft_terminal_outcome_truncate();

CREATE FUNCTION require_gd_draft_account_terminal_readback() RETURNS trigger AS $$
DECLARE
    current_commit RECORD;
    operation_record RECORD;
    terminal_record RECORD;
    expected_outcome VARCHAR(32);
BEGIN
    SELECT workflow_state, canonical_tenant_id, canonical_version_id, request_id, commit_id
      INTO current_commit
    FROM game_design_draft_commit
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
      AND canonical_version_id = NEW.canonical_version_id
      AND request_id = NEW.request_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    IF current_commit.workflow_state NOT IN ('SYNCHRONIZED', 'FAILED_NONPUBLICATION', 'REJECTED') THEN
        RETURN NULL;
    END IF;

    SELECT operation_id
      INTO operation_record
    FROM game_design_draft_terminal_operation
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
      AND canonical_version_id = NEW.canonical_version_id
      AND request_id = NEW.request_id
      AND commit_id = NEW.commit_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF current_commit.workflow_state = 'REJECTED' THEN
        RAISE EXCEPTION 'Account-bound rejection is not a terminal readback; exact final-abort evidence is required'
            USING ERRCODE = 'check_violation';
    END IF;

    expected_outcome :=
        CASE current_commit.workflow_state
            WHEN 'SYNCHRONIZED' THEN 'COMMITTED'
            ELSE 'DEFINITIVELY_ABORTED'
        END;
    SELECT outcome
      INTO terminal_record
    FROM game_design_draft_terminal_outcome
    WHERE operation_id = operation_record.operation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account-bound terminal workflow state requires its same-transaction exact outcome row'
            USING ERRCODE = 'check_violation';
    END IF;
    IF terminal_record.outcome IS DISTINCT FROM expected_outcome THEN
        RAISE EXCEPTION 'Account-bound terminal workflow state requires its same-transaction exact outcome row'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_gd_draft_account_terminal_readback
    AFTER UPDATE ON game_design_draft_commit
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION require_gd_draft_account_terminal_readback();
-- [jooq ignore stop]
