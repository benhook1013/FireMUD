-- A definitive no-commit result is an immutable, owner-local tombstone. It is not permission,
-- an APPLIED result, or evidence that Account's authorization fence may be released.
CREATE TABLE world_draft_terminal_outcome (
    operation_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    commit_id UUID NOT NULL UNIQUE,
    authorization_fence_id UUID NOT NULL UNIQUE,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    version_identity_operation_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL CHECK (local_tenant_key > 0),
    local_version_key BIGINT NOT NULL CHECK (local_version_key > 0),
    owner_binding_json TEXT NOT NULL,
    binding_json TEXT NOT NULL,
    binding_digest VARCHAR(71) NOT NULL CHECK (binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    account_binding_bytes BYTEA NOT NULL CHECK (octet_length(account_binding_bytes) > 0),
    account_binding_digest VARCHAR(71) NOT NULL CHECK (account_binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    affected_units_json JSONB NOT NULL CHECK (jsonb_typeof(affected_units_json) = 'array'),
    outcome VARCHAR(32) NOT NULL CHECK (outcome = 'DEFINITIVELY_ABORTED'),
    outcome_bytes BYTEA NOT NULL CHECK (octet_length(outcome_bytes) > 0),
    outcome_digest VARCHAR(71) NOT NULL CHECK (outcome_digest ~ '^sha256:[0-9a-f]{64}$'),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_world_draft_terminal_non_nil CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND authorization_fence_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT ck_world_draft_terminal_scope CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
    ),
    CONSTRAINT ck_world_draft_terminal_binding CHECK (
        jsonb_typeof(binding_json::jsonb) = 'object'
        AND binding_json::jsonb->>'canonicalTenantId' = canonical_tenant_id::TEXT
        AND binding_json::jsonb->>'canonicalVersionId' = canonical_version_id::TEXT
        AND binding_json::jsonb->>'requestId' = request_id::TEXT
        AND binding_json::jsonb->>'commitId' = commit_id::TEXT
        AND jsonb_typeof(owner_binding_json::jsonb) = 'object'
        AND owner_binding_json::jsonb->>'targetNamespace' = target_namespace
        AND owner_binding_json::jsonb->>'canonicalTenantId' = canonical_tenant_id::TEXT
        AND owner_binding_json::jsonb->>'canonicalVersionId' = canonical_version_id::TEXT
        AND owner_binding_json::jsonb->>'versionIdentityOperationId' = version_identity_operation_id::TEXT
    ),
    CONSTRAINT fk_world_draft_terminal_version_identity FOREIGN KEY (
        version_identity_operation_id,
        target_namespace,
        canonical_tenant_id,
        canonical_version_id,
        local_version_key
    ) REFERENCES world_authored_version_identity (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        canonical_version_id,
        local_version_key
    )
);
CREATE INDEX idx_world_draft_terminal_scope
    ON world_draft_terminal_outcome (target_namespace, canonical_tenant_id, canonical_version_id);

-- [jooq ignore start]
REVOKE ALL ON world_draft_terminal_outcome FROM PUBLIC;

CREATE FUNCTION world_draft_terminal_outcome_guard() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    unit JSONB;
    aggregate_key BIGINT;
    observed_epoch BIGINT;
    expected_epoch BIGINT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'World Draft terminal outcomes are immutable and retained'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO owner_row FROM "${serviceSchema}".world_design_publication_fence_owner
    WHERE target_namespace = NEW.target_namespace
      AND canonical_tenant_id = NEW.canonical_tenant_id
      AND local_tenant_key = NEW.local_tenant_key
      AND version_id = NEW.local_version_key
    FOR UPDATE;
    IF NOT FOUND OR owner_row.owner_freeze_phase <> 'OPEN'
        OR owner_row.current_publication_fence IS NOT NULL THEN
        RAISE EXCEPTION 'First World Draft abort requires the exact OPEN V25 owner row'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_region_draft_commit c
        WHERE c.request_id = NEW.request_id OR c.commit_id = NEW.commit_id
    ) OR EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_topology_draft_commit c
        WHERE c.request_id = NEW.request_id OR c.commit_id = NEW.commit_id
    ) OR EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_authored_topology_identity i
        WHERE i.target_namespace = NEW.target_namespace
          AND i.canonical_tenant_id = NEW.canonical_tenant_id
          AND i.canonical_version_id = NEW.canonical_version_id
          AND (i.request_id = NEW.request_id OR i.commit_id = NEW.commit_id)
    ) OR EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_design_revision_ledger r
        WHERE r.tenant_id = NEW.local_tenant_key
          AND r.version_id = NEW.local_version_key
          AND r.commit_id = NEW.commit_id::TEXT
    ) THEN
        RAISE EXCEPTION 'Retained World application history cannot be terminalized as no-commit'
            USING ERRCODE = '23514';
    END IF;

    FOR unit IN SELECT value FROM jsonb_array_elements(NEW.affected_units_json) LOOP
        IF unit->>'owner' <> 'WORLD_MANAGEMENT' THEN
            RAISE EXCEPTION 'World terminal affected-unit vector contains a foreign owner'
                USING ERRCODE = '23514';
        END IF;
        expected_epoch := (unit->>'expectedEpoch')::BIGINT;
        IF unit->>'scopeType' = 'AGGREGATE' THEN
            IF unit->>'aggregateId' ~ '^[1-9][0-9]*$' THEN
                aggregate_key := (unit->>'aggregateId')::BIGINT;
            ELSE
                SELECT private_row_key INTO aggregate_key
                FROM "${serviceSchema}".world_authored_topology_identity
                WHERE target_namespace = NEW.target_namespace
                  AND canonical_tenant_id = NEW.canonical_tenant_id
                  AND canonical_version_id = NEW.canonical_version_id
                  AND family = unit->>'aggregateType'
                  AND template_id = (unit->>'aggregateId')::UUID;
                IF NOT FOUND AND expected_epoch <> 0 THEN
                    RAISE EXCEPTION 'World topology mapping is absent for a nonzero expected epoch'
                        USING ERRCODE = '23514';
                END IF;
            END IF;
            observed_epoch := 0;
            IF aggregate_key IS NOT NULL THEN
                SELECT draft_revision_epoch INTO observed_epoch
                FROM "${serviceSchema}".world_design_aggregate_epoch
                WHERE tenant_id = NEW.local_tenant_key
                  AND version_id = NEW.local_version_key
                  AND aggregate_type = unit->>'aggregateType'
                  AND aggregate_id = aggregate_key;
                IF NOT FOUND THEN observed_epoch := 0; END IF;
            END IF;
            IF observed_epoch IS DISTINCT FROM expected_epoch THEN
                RAISE EXCEPTION 'Affected World aggregate advanced before definitive abort'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            observed_epoch := 0;
            SELECT draft_scope_revision_epoch INTO observed_epoch
            FROM "${serviceSchema}".world_design_scope_epoch
            WHERE tenant_id = NEW.local_tenant_key
              AND version_id = NEW.local_version_key
              AND scope_type = unit->>'scopeType'
              AND scope_id = unit->>'scopeId';
            IF NOT FOUND THEN observed_epoch := 0; END IF;
            IF observed_epoch IS DISTINCT FROM expected_epoch THEN
                RAISE EXCEPTION 'Affected World scope advanced before definitive abort'
                    USING ERRCODE = '23514';
            END IF;
        END IF;
        aggregate_key := NULL;
    END LOOP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_world_draft_terminal_outcome_guard
    BEFORE INSERT OR UPDATE OR DELETE ON world_draft_terminal_outcome
    FOR EACH ROW EXECUTE FUNCTION world_draft_terminal_outcome_guard();
CREATE TRIGGER trg_world_draft_terminal_outcome_no_truncate
    BEFORE TRUNCATE ON world_draft_terminal_outcome
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE FUNCTION world_reject_aborted_draft_attempt() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    request_identity UUID;
    commit_identity UUID;
    target_tenant UUID;
    target_version UUID;
    target_namespace TEXT;
BEGIN
    IF TG_TABLE_NAME = 'world_region_draft_execution_manifest' THEN
        request_identity := (NEW.complete_binding->>'requestId')::UUID;
        commit_identity := (NEW.complete_binding->>'commitId')::UUID;
        target_tenant := (NEW.complete_binding->>'canonicalTenantId')::UUID;
        target_version := (NEW.complete_binding->>'canonicalVersionId')::UUID;
        target_namespace := NEW.owner_binding->>'targetNamespace';
    ELSE
        request_identity := NEW.request_id;
        commit_identity := NEW.commit_id;
        target_tenant := NEW.canonical_tenant_id;
        target_version := NEW.canonical_version_id;
        target_namespace := NEW.target_namespace;
    END IF;
    IF EXISTS (
        SELECT 1 FROM "${serviceSchema}".world_draft_terminal_outcome terminal
        WHERE terminal.target_namespace = target_namespace
          AND terminal.canonical_tenant_id = target_tenant
          AND terminal.canonical_version_id = target_version
          AND (terminal.request_id = request_identity OR terminal.commit_id = commit_identity)
    ) THEN
        RAISE EXCEPTION 'World Draft operation has immutable definitive no-commit evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- The guarded writers first lock the same V25 owner row. The region function creates this
-- manifest before any content or epoch change; topology identity insertion is also protected
-- by its existing deferred history FK. These gates exclude a delayed raw SQL call as well as
-- repository retries after an ABORTED result.
CREATE TRIGGER trg_world_region_manifest_aborted_attempt
    BEFORE INSERT ON world_region_draft_execution_manifest
    FOR EACH ROW EXECUTE FUNCTION world_reject_aborted_draft_attempt();
CREATE TRIGGER trg_world_region_commit_aborted_attempt
    BEFORE INSERT ON world_region_draft_commit
    FOR EACH ROW EXECUTE FUNCTION world_reject_aborted_draft_attempt();
CREATE TRIGGER trg_world_topology_identity_aborted_attempt
    BEFORE INSERT ON world_authored_topology_identity
    FOR EACH ROW EXECUTE FUNCTION world_reject_aborted_draft_attempt();
CREATE TRIGGER trg_world_topology_commit_aborted_attempt
    BEFORE INSERT ON world_topology_draft_commit
    FOR EACH ROW EXECUTE FUNCTION world_reject_aborted_draft_attempt();

REVOKE ALL ON FUNCTION world_draft_terminal_outcome_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION world_reject_aborted_draft_attempt() FROM PUBLIC;
-- [jooq ignore stop]
