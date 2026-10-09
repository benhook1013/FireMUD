CREATE TABLE game_design_draft_commit (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    game_design_version_row_id BIGINT NOT NULL,
    game_design_version_tenant_key VARCHAR(36) NOT NULL,
    source_game_row_id BIGINT NOT NULL,
    source_game_tenant_key VARCHAR(36) NOT NULL,
    source_provenance_kind VARCHAR(32) NOT NULL,
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    base_commit_id VARCHAR(256) NOT NULL,
    input_digest VARCHAR(71) NOT NULL,
    binding_json TEXT NOT NULL,
    workflow_state VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_draft_commit PRIMARY KEY (
        canonical_tenant_id,
        canonical_version_id,
        request_id
    ),
    CONSTRAINT uq_gd_draft_commit_target_commit UNIQUE (
        canonical_tenant_id,
        canonical_version_id,
        commit_id
    ),
    CONSTRAINT uq_gd_draft_commit_exact_identity UNIQUE (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ),
    CONSTRAINT fk_gd_draft_commit_canonical_version FOREIGN KEY (
        game_design_version_row_id,
        canonical_tenant_id,
        canonical_version_id
    ) REFERENCES version (id, canonical_tenant_id, canonical_version_id) ON DELETE RESTRICT,
    CONSTRAINT fk_gd_draft_commit_source_game FOREIGN KEY (
        source_game_tenant_key,
        source_game_row_id
    ) REFERENCES game (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT chk_gd_draft_commit_identity CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND game_design_version_row_id > 0
        AND source_game_row_id > 0
        AND game_design_version_tenant_key = source_game_tenant_key
        AND char_length(game_design_version_tenant_key) BETWEEN 1 AND 36
        AND game_design_version_tenant_key !~ '^[[:space:]]*$'
        AND source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')
        AND octet_length(base_commit_id) BETWEEN 1 AND 256
        AND base_commit_id !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gd_draft_commit_digest CHECK (input_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_gd_draft_commit_binding_json CHECK (
        jsonb_typeof(binding_json::jsonb) = 'object'
    ),
    CONSTRAINT chk_gd_draft_commit_state CHECK (
        workflow_state IN (
            'QUEUED', 'APPLYING', 'RECONCILIATION_REQUIRED', 'SYNCHRONIZED',
            'REJECTED', 'FAILED_NONPUBLICATION'
        )
    )
);

CREATE TABLE game_design_draft_commit_owner_result (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL,
    owner VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    result_commit_id UUID,
    result_binding_digest VARCHAR(71),
    result_identity VARCHAR(512),
    result_bytes BYTEA,
    applied_units_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_draft_commit_owner_result PRIMARY KEY (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        owner
    ),
    CONSTRAINT fk_gd_draft_commit_owner_result_commit FOREIGN KEY (
        canonical_tenant_id,
        canonical_version_id,
        request_id
    ) REFERENCES game_design_draft_commit (
        canonical_tenant_id,
        canonical_version_id,
        request_id
    ) ON DELETE RESTRICT,
    CONSTRAINT chk_gd_draft_commit_owner CHECK (
        owner IN (
            'WORLD_MANAGEMENT',
            'ENTITY_MANAGEMENT',
            'GAME_LOGIC',
            'AUTOMATION_SCRIPTING',
            'GAME_DESIGN_CONTROL_PLANE'
        )
    ),
    CONSTRAINT chk_gd_draft_commit_owner_status CHECK (
        status IN ('NOT_ATTEMPTED', 'IN_PROGRESS', 'APPLIED', 'REJECTED', 'UNKNOWN')
    ),
    CONSTRAINT chk_gd_draft_commit_owner_result_shape CHECK (
        (
            status IN ('NOT_ATTEMPTED', 'IN_PROGRESS')
            AND result_commit_id IS NULL
            AND result_binding_digest IS NULL
            AND result_identity IS NULL
            AND result_bytes IS NULL
            AND applied_units_json IS NULL
        )
        OR
        (
            status = 'UNKNOWN'
            AND (
                (result_identity IS NULL AND result_bytes IS NULL)
                OR (result_identity IS NOT NULL AND result_bytes IS NOT NULL)
            )
            AND (
                (result_commit_id IS NULL AND result_binding_digest IS NULL AND applied_units_json IS NULL)
                OR (result_commit_id IS NOT NULL AND result_binding_digest IS NOT NULL)
            )
        )
        OR
        (
            status IN ('APPLIED', 'REJECTED')
            AND result_commit_id IS NOT NULL
            AND result_binding_digest IS NOT NULL
            AND result_identity IS NOT NULL
            AND result_bytes IS NOT NULL
            AND applied_units_json IS NOT NULL
            AND jsonb_typeof(applied_units_json::jsonb) = 'array'
            AND (status <> 'REJECTED' OR applied_units_json::jsonb = '[]'::jsonb)
        )
    ),
    CONSTRAINT chk_gd_draft_commit_owner_result_digest CHECK (
        result_binding_digest IS NULL OR result_binding_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_gd_draft_commit_owner_result_identity CHECK (
        result_identity IS NULL OR (
            char_length(result_identity) BETWEEN 1 AND 512
            AND result_identity !~ '^[[:space:]]*$'
        )
    )
);

CREATE TABLE game_design_draft_commit_visibility_fence (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    input_digest VARCHAR(71) NOT NULL,
    result_vector_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_draft_commit_visibility_fence PRIMARY KEY (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ),
    CONSTRAINT fk_gd_draft_commit_visibility_fence_commit FOREIGN KEY (
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
    CONSTRAINT chk_gd_draft_commit_visibility_digest CHECK (input_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_gd_draft_commit_visibility_vector CHECK (
        jsonb_typeof(result_vector_json::jsonb) = 'array'
    )
);

CREATE TABLE game_design_draft_commit_visibility (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    input_digest VARCHAR(71) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_draft_commit_visibility PRIMARY KEY (
        canonical_tenant_id,
        canonical_version_id
    ),
    CONSTRAINT fk_gd_draft_commit_visibility_fence FOREIGN KEY (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ) REFERENCES game_design_draft_commit_visibility_fence (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ) ON DELETE RESTRICT,
    CONSTRAINT chk_gd_draft_commit_visibility_pointer_digest CHECK (
        input_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE TABLE game_design_draft_commit_application_slot (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_draft_commit_application_slot PRIMARY KEY (
        canonical_tenant_id,
        canonical_version_id
    ),
    CONSTRAINT fk_gd_draft_commit_application_slot_commit FOREIGN KEY (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ) REFERENCES game_design_draft_commit (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ) ON DELETE RESTRICT
);

CREATE TABLE game_design_draft_commit_final_abort (
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    input_digest VARCHAR(71) NOT NULL,
    binding_json TEXT NOT NULL,
    abort_bytes BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_draft_commit_final_abort PRIMARY KEY (
        canonical_tenant_id,
        canonical_version_id,
        request_id,
        commit_id
    ),
    CONSTRAINT fk_gd_draft_commit_final_abort_commit FOREIGN KEY (
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
    CONSTRAINT chk_gd_draft_commit_final_abort_digest CHECK (
        input_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_gd_draft_commit_final_abort_binding CHECK (
        jsonb_typeof(binding_json::jsonb) = 'object'
    ),
    CONSTRAINT chk_gd_draft_commit_final_abort_bytes CHECK (octet_length(abort_bytes) > 0)
);

-- [jooq ignore start]
CREATE FUNCTION gd_draft_commit_has_exact_terminal_abort_vector(
    target_tenant UUID,
    target_version UUID,
    target_request UUID,
    target_commit UUID
) RETURNS BOOLEAN AS $$
DECLARE
    parent_record RECORD;
    expected_count INTEGER;
    actual_count INTEGER;
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
    IF jsonb_typeof(parent_record.binding_json::jsonb -> 'requiredOwners') IS DISTINCT FROM 'array'
        OR jsonb_array_length(parent_record.binding_json::jsonb -> 'requiredOwners') = 0 THEN
        RETURN FALSE;
    END IF;

    expected_count := jsonb_array_length(parent_record.binding_json::jsonb -> 'requiredOwners');
    SELECT count(*) INTO actual_count
    FROM game_design_draft_commit_owner_result
    WHERE canonical_tenant_id = target_tenant
      AND canonical_version_id = target_version
      AND request_id = target_request;
    IF actual_count <> expected_count THEN
        RETURN FALSE;
    END IF;

    RETURN NOT EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(parent_record.binding_json::jsonb -> 'requiredOwners') required(owner)
        LEFT JOIN game_design_draft_commit_owner_result outcome
          ON outcome.canonical_tenant_id = target_tenant
         AND outcome.canonical_version_id = target_version
         AND outcome.request_id = target_request
         AND outcome.owner = required.owner
        WHERE outcome.owner IS NULL
           OR outcome.status NOT IN ('APPLIED', 'REJECTED')
           OR outcome.result_commit_id IS DISTINCT FROM target_commit
           OR outcome.result_binding_digest IS DISTINCT FROM parent_record.input_digest
           OR outcome.result_identity IS NULL
           OR outcome.result_bytes IS NULL
           OR outcome.applied_units_json IS NULL
    );
END;
$$ LANGUAGE plpgsql STABLE;

CREATE FUNCTION enforce_gd_draft_commit_final_abort() RETURNS trigger AS $$
DECLARE
    parent_record RECORD;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Game Design Draft final-abort evidence is immutable and retained'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT commit_id, input_digest, binding_json, workflow_state
      INTO parent_record
    FROM game_design_draft_commit
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
      AND canonical_version_id = NEW.canonical_version_id
      AND request_id = NEW.request_id
      AND commit_id = NEW.commit_id
    FOR UPDATE;
    IF NOT FOUND
        OR parent_record.input_digest IS DISTINCT FROM NEW.input_digest
        OR parent_record.binding_json IS DISTINCT FROM NEW.binding_json
        OR parent_record.workflow_state NOT IN ('APPLYING', 'RECONCILIATION_REQUIRED') THEN
        RAISE EXCEPTION 'Final-abort evidence must bind the exact active Draft commit'
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
        SELECT 1 FROM game_design_draft_commit_visibility_fence f
        WHERE f.canonical_tenant_id = NEW.canonical_tenant_id
          AND f.canonical_version_id = NEW.canonical_version_id
          AND f.request_id = NEW.request_id
          AND f.commit_id = NEW.commit_id
    ) THEN
        RAISE EXCEPTION 'A successful Draft visibility fence cannot be final-aborted'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT gd_draft_commit_has_exact_terminal_abort_vector(
        NEW.canonical_tenant_id,
        NEW.canonical_version_id,
        NEW.request_id,
        NEW.commit_id
    ) THEN
        RAISE EXCEPTION 'Final-abort evidence requires every exact owner result to be terminal'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_final_abort
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_draft_commit_final_abort
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_commit_final_abort();

CREATE FUNCTION reject_gd_draft_commit_final_abort_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM game_design_draft_commit_final_abort) THEN
        RAISE EXCEPTION 'Game Design Draft final-abort evidence cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_final_abort_no_truncate
    BEFORE TRUNCATE ON game_design_draft_commit_final_abort
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_gd_draft_commit_final_abort_truncate();

CREATE FUNCTION enforce_gd_draft_commit_binding() RETURNS trigger AS $$
DECLARE
    version_matches BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Game Design Draft commit bindings are immutable and retained'
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
            OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
            OR NEW.game_design_version_row_id IS DISTINCT FROM OLD.game_design_version_row_id
            OR NEW.game_design_version_tenant_key IS DISTINCT FROM OLD.game_design_version_tenant_key
            OR NEW.source_game_row_id IS DISTINCT FROM OLD.source_game_row_id
            OR NEW.source_game_tenant_key IS DISTINCT FROM OLD.source_game_tenant_key
            OR NEW.source_provenance_kind IS DISTINCT FROM OLD.source_provenance_kind
            OR NEW.request_id IS DISTINCT FROM OLD.request_id
            OR NEW.commit_id IS DISTINCT FROM OLD.commit_id
            OR NEW.base_commit_id IS DISTINCT FROM OLD.base_commit_id
            OR NEW.input_digest IS DISTINCT FROM OLD.input_digest
            OR NEW.binding_json IS DISTINCT FROM OLD.binding_json
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
            RAISE EXCEPTION 'Game Design Draft commit binding identity is immutable'
                USING ERRCODE = 'check_violation';
        END IF;

        IF NEW.workflow_state IS DISTINCT FROM OLD.workflow_state THEN
            IF OLD.workflow_state IN ('SYNCHRONIZED', 'REJECTED', 'FAILED_NONPUBLICATION')
                OR NOT (
                    (OLD.workflow_state = 'QUEUED'
                        AND NEW.workflow_state = 'APPLYING')
                    OR (OLD.workflow_state = 'APPLYING'
                        AND NEW.workflow_state IN (
                            'RECONCILIATION_REQUIRED', 'SYNCHRONIZED', 'REJECTED',
                            'FAILED_NONPUBLICATION'
                        ))
                    OR (OLD.workflow_state = 'RECONCILIATION_REQUIRED'
                        AND NEW.workflow_state IN (
                            'SYNCHRONIZED', 'REJECTED', 'FAILED_NONPUBLICATION'
                        ))
                ) THEN
                RAISE EXCEPTION 'Invalid Game Design Draft commit workflow transition'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSIF NEW.updated_at IS DISTINCT FROM OLD.updated_at THEN
            RAISE EXCEPTION 'An unchanged Draft commit workflow state is not rewritten'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.workflow_state = 'REJECTED'
            AND (
                EXISTS (
                    SELECT 1 FROM game_design_draft_commit_owner_result r
                    WHERE r.canonical_tenant_id = OLD.canonical_tenant_id
                      AND r.canonical_version_id = OLD.canonical_version_id
                      AND r.request_id = OLD.request_id
                      AND r.status IN ('APPLIED', 'IN_PROGRESS', 'UNKNOWN')
                )
                OR NOT EXISTS (
                    SELECT 1 FROM game_design_draft_commit_owner_result r
                    WHERE r.canonical_tenant_id = OLD.canonical_tenant_id
                      AND r.canonical_version_id = OLD.canonical_version_id
                      AND r.request_id = OLD.request_id
                      AND r.status = 'REJECTED'
                )
            ) THEN
            RAISE EXCEPTION 'Unresolved or applied Draft owner outcomes cannot be finalized REJECTED'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.workflow_state = 'FAILED_NONPUBLICATION'
            AND (
                NOT EXISTS (
                    SELECT 1 FROM game_design_draft_commit_final_abort abort
                    WHERE abort.canonical_tenant_id = OLD.canonical_tenant_id
                      AND abort.canonical_version_id = OLD.canonical_version_id
                      AND abort.request_id = OLD.request_id
                      AND abort.commit_id = OLD.commit_id
                      AND abort.input_digest = OLD.input_digest
                      AND abort.binding_json = OLD.binding_json
                )
                OR NOT gd_draft_commit_has_exact_terminal_abort_vector(
                    OLD.canonical_tenant_id,
                    OLD.canonical_version_id,
                    OLD.request_id,
                    OLD.commit_id
                )
                OR EXISTS (
                    SELECT 1 FROM game_design_draft_commit_visibility_fence f
                    WHERE f.canonical_tenant_id = OLD.canonical_tenant_id
                      AND f.canonical_version_id = OLD.canonical_version_id
                      AND f.request_id = OLD.request_id
                      AND f.commit_id = OLD.commit_id
                )
            ) THEN
            RAISE EXCEPTION 'Failed nonpublication requires exact final-abort evidence and no visibility fence'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.binding_json::jsonb ->> 'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::text
        OR NEW.binding_json::jsonb ->> 'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::text
        OR NEW.binding_json::jsonb ->> 'requestId' IS DISTINCT FROM NEW.request_id::text
        OR NEW.binding_json::jsonb ->> 'commitId' IS DISTINCT FROM NEW.commit_id::text
        OR NEW.binding_json::jsonb ->> 'baseCommitId' IS DISTINCT FROM NEW.base_commit_id
        OR NEW.binding_json::jsonb ->> 'schemaVersion' IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Stored Draft commit JSON differs from its indexed identity'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.binding_json::jsonb -> 'target' ->> 'gameDesignVersionRowId'
            IS DISTINCT FROM NEW.game_design_version_row_id::text
        OR NEW.binding_json::jsonb -> 'target' ->> 'gameDesignVersionTenantKey'
            IS DISTINCT FROM NEW.game_design_version_tenant_key
        OR NEW.binding_json::jsonb -> 'target' ->> 'sourceGameRowId'
            IS DISTINCT FROM NEW.source_game_row_id::text
        OR NEW.binding_json::jsonb -> 'target' ->> 'sourceGameTenantKey'
            IS DISTINCT FROM NEW.source_game_tenant_key
        OR NEW.binding_json::jsonb -> 'target' ->> 'sourceProvenanceKind'
            IS DISTINCT FROM NEW.source_provenance_kind THEN
        RAISE EXCEPTION 'Stored Draft commit JSON differs from its private Version source proof'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT EXISTS (
        SELECT 1
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
          AND g.id = NEW.source_game_row_id
          AND g.tenant_id = NEW.source_game_tenant_key
    ) INTO version_matches;
    IF NOT version_matches THEN
        RAISE EXCEPTION 'Draft commit target lacks exact canonical Game Design Version source proof'
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_binding
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_draft_commit
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_commit_binding();

CREATE FUNCTION reject_gd_draft_commit_binding_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM game_design_draft_commit) THEN
        RAISE EXCEPTION 'Game Design Draft commit bindings cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_binding_no_truncate
    BEFORE TRUNCATE ON game_design_draft_commit
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_gd_draft_commit_binding_truncate();

CREATE FUNCTION enforce_gd_draft_commit_owner_result() RETURNS trigger AS $$
DECLARE
    parent_record RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Game Design Draft owner results are retained'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT commit_id, input_digest, binding_json
      INTO parent_record
    FROM game_design_draft_commit
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
      AND canonical_version_id = NEW.canonical_version_id
      AND request_id = NEW.request_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Game Design Draft owner result has no exact commit binding'
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF NOT ((parent_record.binding_json::jsonb -> 'requiredOwners') ? NEW.owner) THEN
        RAISE EXCEPTION 'Game Design Draft owner result is outside the bound required owner set'
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'NOT_ATTEMPTED'
            OR NEW.result_commit_id IS NOT NULL
            OR NEW.result_binding_digest IS NOT NULL
            OR NEW.result_identity IS NOT NULL
            OR NEW.result_bytes IS NOT NULL
            OR NEW.applied_units_json IS NOT NULL THEN
            RAISE EXCEPTION 'Draft commit owner result must begin NOT_ATTEMPTED without evidence'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
        OR NEW.canonical_version_id IS DISTINCT FROM OLD.canonical_version_id
        OR NEW.request_id IS DISTINCT FROM OLD.request_id
        OR NEW.owner IS DISTINCT FROM OLD.owner
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Game Design Draft owner result identity is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = OLD.status THEN
        IF NEW.result_commit_id IS DISTINCT FROM OLD.result_commit_id
            OR NEW.result_binding_digest IS DISTINCT FROM OLD.result_binding_digest
            OR NEW.result_identity IS DISTINCT FROM OLD.result_identity
            OR NEW.result_bytes IS DISTINCT FROM OLD.result_bytes
            OR NEW.applied_units_json IS DISTINCT FROM OLD.applied_units_json
            OR NEW.updated_at IS DISTINCT FROM OLD.updated_at THEN
            RAISE EXCEPTION 'Exact Draft owner result retries do not rewrite persisted evidence'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NOT (
        (OLD.status = 'NOT_ATTEMPTED' AND NEW.status = 'IN_PROGRESS')
        OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('APPLIED', 'REJECTED', 'UNKNOWN'))
        OR (OLD.status = 'UNKNOWN' AND NEW.status IN ('APPLIED', 'REJECTED'))
    ) THEN
        RAISE EXCEPTION 'Invalid Game Design Draft owner result transition'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status IN ('APPLIED', 'REJECTED')
        AND (NEW.result_commit_id IS DISTINCT FROM parent_record.commit_id
            OR NEW.result_binding_digest IS DISTINCT FROM parent_record.input_digest) THEN
        RAISE EXCEPTION 'Game Design Draft owner result does not match its exact commit binding'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_owner_result
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_draft_commit_owner_result
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_commit_owner_result();

CREATE FUNCTION enforce_gd_draft_commit_visibility_fence() RETURNS trigger AS $$
DECLARE
    parent_record RECORD;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Game Design Draft visibility fences are immutable and retained'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT commit_id, input_digest, workflow_state, binding_json
      INTO parent_record
    FROM game_design_draft_commit
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
      AND canonical_version_id = NEW.canonical_version_id
      AND request_id = NEW.request_id
    FOR UPDATE;
    IF NOT FOUND
        OR parent_record.commit_id IS DISTINCT FROM NEW.commit_id
        OR parent_record.input_digest IS DISTINCT FROM NEW.input_digest
        OR parent_record.workflow_state NOT IN ('APPLYING', 'RECONCILIATION_REQUIRED') THEN
        RAISE EXCEPTION 'Draft visibility fence lacks a matching nonterminal commit binding'
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
        SELECT 1 FROM game_design_draft_commit_final_abort abort
        WHERE abort.canonical_tenant_id = NEW.canonical_tenant_id
          AND abort.canonical_version_id = NEW.canonical_version_id
          AND abort.request_id = NEW.request_id
          AND abort.commit_id = NEW.commit_id
    ) THEN
        RAISE EXCEPTION 'Final-aborted Draft commits cannot gain a visibility fence'
            USING ERRCODE = 'check_violation';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM jsonb_array_elements_text(parent_record.binding_json::jsonb -> 'requiredOwners') required(owner)
        LEFT JOIN game_design_draft_commit_owner_result outcome
          ON outcome.canonical_tenant_id = NEW.canonical_tenant_id
         AND outcome.canonical_version_id = NEW.canonical_version_id
         AND outcome.request_id = NEW.request_id
         AND outcome.owner = required.owner
        WHERE outcome.status IS DISTINCT FROM 'APPLIED'
    ) THEN
        RAISE EXCEPTION 'Draft visibility fence requires every exact required owner to be APPLIED'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_visibility_fence
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_draft_commit_visibility_fence
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_commit_visibility_fence();

CREATE FUNCTION reject_gd_draft_commit_visibility_fence_truncate() RETURNS trigger AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM game_design_draft_commit_visibility_fence) THEN
        RAISE EXCEPTION 'Game Design Draft visibility fences cannot be truncated'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_visibility_fence_no_truncate
    BEFORE TRUNCATE ON game_design_draft_commit_visibility_fence
    FOR EACH STATEMENT
    EXECUTE FUNCTION reject_gd_draft_commit_visibility_fence_truncate();

CREATE FUNCTION enforce_gd_draft_commit_visibility_pointer() RETURNS trigger AS $$
DECLARE
    fence_digest VARCHAR(71);
    workflow_state VARCHAR(32);
BEGIN
    SELECT f.input_digest, c.workflow_state INTO fence_digest, workflow_state
    FROM game_design_draft_commit_visibility_fence f
    JOIN game_design_draft_commit c
      ON c.canonical_tenant_id = f.canonical_tenant_id
     AND c.canonical_version_id = f.canonical_version_id
     AND c.request_id = f.request_id
     AND c.commit_id = f.commit_id
    WHERE f.canonical_tenant_id = NEW.canonical_tenant_id
      AND f.canonical_version_id = NEW.canonical_version_id
      AND f.request_id = NEW.request_id
      AND f.commit_id = NEW.commit_id;
    IF NOT FOUND OR fence_digest IS DISTINCT FROM NEW.input_digest
        OR workflow_state = 'FAILED_NONPUBLICATION'
        OR EXISTS (
            SELECT 1 FROM game_design_draft_commit_final_abort abort
            WHERE abort.canonical_tenant_id = NEW.canonical_tenant_id
              AND abort.canonical_version_id = NEW.canonical_version_id
              AND abort.request_id = NEW.request_id
              AND abort.commit_id = NEW.commit_id
        ) THEN
        RAISE EXCEPTION 'Draft visibility pointer must name its exact durable fence'
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_visibility_pointer
    BEFORE INSERT OR UPDATE ON game_design_draft_commit_visibility
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_commit_visibility_pointer();

CREATE FUNCTION enforce_gd_draft_commit_application_slot() RETURNS trigger AS $$
DECLARE
    parent_record RECORD;
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT workflow_state, input_digest, binding_json
          INTO parent_record
        FROM game_design_draft_commit
        WHERE canonical_tenant_id = OLD.canonical_tenant_id
          AND canonical_version_id = OLD.canonical_version_id
          AND request_id = OLD.request_id
          AND commit_id = OLD.commit_id
        FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Draft application slot has no exact commit binding'
                USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF parent_record.workflow_state = 'SYNCHRONIZED' THEN
            IF NOT EXISTS (
                SELECT 1 FROM game_design_draft_commit_visibility_fence f
                WHERE f.canonical_tenant_id = OLD.canonical_tenant_id
                  AND f.canonical_version_id = OLD.canonical_version_id
                  AND f.request_id = OLD.request_id
                  AND f.commit_id = OLD.commit_id
                  AND f.input_digest = parent_record.input_digest
            ) OR EXISTS (
                SELECT 1
                FROM jsonb_array_elements_text(parent_record.binding_json::jsonb -> 'requiredOwners') required(owner)
                LEFT JOIN game_design_draft_commit_owner_result outcome
                  ON outcome.canonical_tenant_id = OLD.canonical_tenant_id
                 AND outcome.canonical_version_id = OLD.canonical_version_id
                 AND outcome.request_id = OLD.request_id
                 AND outcome.owner = required.owner
                WHERE outcome.status IS DISTINCT FROM 'APPLIED'
            ) THEN
                RAISE EXCEPTION 'Synchronized Draft application slot requires its exact complete fence'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSIF parent_record.workflow_state = 'REJECTED' THEN
            IF EXISTS (
                SELECT 1 FROM game_design_draft_commit_owner_result r
                WHERE r.canonical_tenant_id = OLD.canonical_tenant_id
                  AND r.canonical_version_id = OLD.canonical_version_id
                  AND r.request_id = OLD.request_id
                  AND r.status IN ('APPLIED', 'IN_PROGRESS', 'UNKNOWN')
            ) OR NOT EXISTS (
                SELECT 1 FROM game_design_draft_commit_owner_result r
                WHERE r.canonical_tenant_id = OLD.canonical_tenant_id
                  AND r.canonical_version_id = OLD.canonical_version_id
                  AND r.request_id = OLD.request_id
                  AND r.status = 'REJECTED'
            ) THEN
                RAISE EXCEPTION 'Rejected Draft application slot cannot hide unresolved or applied owners'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSIF parent_record.workflow_state = 'FAILED_NONPUBLICATION' THEN
            IF NOT EXISTS (
                SELECT 1 FROM game_design_draft_commit_final_abort abort
                WHERE abort.canonical_tenant_id = OLD.canonical_tenant_id
                  AND abort.canonical_version_id = OLD.canonical_version_id
                  AND abort.request_id = OLD.request_id
                  AND abort.commit_id = OLD.commit_id
                  AND abort.input_digest = parent_record.input_digest
                  AND abort.binding_json = parent_record.binding_json
            ) OR NOT gd_draft_commit_has_exact_terminal_abort_vector(
                OLD.canonical_tenant_id,
                OLD.canonical_version_id,
                OLD.request_id,
                OLD.commit_id
            ) OR EXISTS (
                SELECT 1 FROM game_design_draft_commit_visibility_fence f
                WHERE f.canonical_tenant_id = OLD.canonical_tenant_id
                  AND f.canonical_version_id = OLD.canonical_version_id
                  AND f.request_id = OLD.request_id
                  AND f.commit_id = OLD.commit_id
            ) THEN
                RAISE EXCEPTION 'Failed nonpublication slot release requires exact abort proof and terminal owners'
                    USING ERRCODE = 'check_violation';
            END IF;
        ELSE
            RAISE EXCEPTION 'Draft application slot cannot be released before a terminal outcome'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Game Design Draft application slot identity is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT workflow_state INTO parent_record
    FROM game_design_draft_commit
    WHERE canonical_tenant_id = NEW.canonical_tenant_id
      AND canonical_version_id = NEW.canonical_version_id
      AND request_id = NEW.request_id
      AND commit_id = NEW.commit_id
    FOR UPDATE;
    IF NOT FOUND OR parent_record.workflow_state IN (
        'SYNCHRONIZED', 'REJECTED', 'FAILED_NONPUBLICATION'
    ) THEN
        RAISE EXCEPTION 'Draft application slot requires an exact nonterminal commit'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_gd_draft_commit_application_slot
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_draft_commit_application_slot
    FOR EACH ROW
    EXECUTE FUNCTION enforce_gd_draft_commit_application_slot();
-- [jooq ignore stop]
