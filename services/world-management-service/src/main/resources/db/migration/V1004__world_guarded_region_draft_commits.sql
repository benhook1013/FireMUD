-- Complete owner-local component storage only: no Account permission or publication proof.
ALTER TABLE world_authored_version_identity
    ADD CONSTRAINT uq_world_region_commit_identity UNIQUE (
        operation_id, target_namespace, canonical_tenant_id, canonical_version_id, local_version_key
    );

CREATE TABLE world_region_draft_commit (
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    request_id UUID PRIMARY KEY,
    commit_id UUID NOT NULL UNIQUE,
    version_identity_operation_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    local_version_key BIGINT NOT NULL,
    owner_binding_json TEXT NOT NULL,
    binding_json TEXT NOT NULL,
    binding_digest VARCHAR(71) NOT NULL,
    graph_bytes BYTEA NOT NULL,
    graph_sha256 VARCHAR(64) NOT NULL,
    result_bytes BYTEA NOT NULL,
    storage_status VARCHAR(32) NOT NULL,
    stored_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_world_region_commit_identity FOREIGN KEY (
        version_identity_operation_id, target_namespace, canonical_tenant_id,
        canonical_version_id, local_version_key
    ) REFERENCES world_authored_version_identity (
        operation_id, target_namespace, canonical_tenant_id,
        canonical_version_id, local_version_key
    ),
    CONSTRAINT ck_world_region_commit_evidence CHECK (
        binding_digest ~ '^sha256:[0-9a-f]{64}$'
        AND graph_sha256 ~ '^[0-9a-f]{64}$'
        AND octet_length(graph_bytes) > 0 AND octet_length(result_bytes) > 0
        AND storage_status = 'STORED_PERMISSION_UNVERIFIED'
        AND local_tenant_key > 0 AND local_version_key > 0
    )
);

-- The function below creates and consumes these exact row transitions within its execution.
-- No manifest survives the function, and ordinary application code never inserts one.
CREATE TABLE world_region_draft_execution_manifest (
    transaction_id BIGINT NOT NULL,
    operation_id UUID NOT NULL,
    table_name VARCHAR(50) NOT NULL,
    row_id BIGINT NOT NULL,
    row_operation TEXT NOT NULL,
    version_identity_operation_id UUID NOT NULL REFERENCES world_authored_version_identity(operation_id),
    local_tenant_key BIGINT NOT NULL,
    local_version_key BIGINT NOT NULL,
    owner_binding JSONB NOT NULL,
    complete_binding JSONB NOT NULL,
    expected_old JSONB,
    expected_new JSONB NOT NULL,
    PRIMARY KEY (transaction_id, operation_id, table_name, row_id),
    CHECK (table_name IN ('region', 'world_design_aggregate_epoch', 'world_design_scope_epoch')),
    CHECK (row_operation IN ('INSERT', 'UPDATE')),
    CHECK ((row_operation = 'INSERT' AND expected_old IS NULL AND table_name <> 'region')
        OR (row_operation = 'UPDATE' AND expected_old IS NOT NULL))
);

-- [jooq ignore start]
REVOKE ALL ON world_region_draft_execution_manifest FROM PUBLIC;

CREATE TRIGGER trg_world_region_commit_immutable
    BEFORE UPDATE OR DELETE ON world_region_draft_commit
    FOR EACH ROW EXECUTE FUNCTION world_reject_authored_source_history_mutation();
CREATE TRIGGER trg_world_region_commit_no_truncate
    BEFORE TRUNCATE ON world_region_draft_commit
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE FUNCTION world_consume_region_execution_manifest(
    actual_table TEXT, actual_operation TEXT, actual_old JSONB, actual_new JSONB
) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    consumed BIGINT;
BEGIN
    IF actual_operation NOT IN ('INSERT', 'UPDATE') OR actual_new IS NULL THEN
        RETURN FALSE;
    END IF;
    DELETE FROM "${serviceSchema}".world_region_draft_execution_manifest m
    USING "${serviceSchema}".world_authored_version_identity v,
        "${serviceSchema}".world_design_publication_fence_owner o
    WHERE m.transaction_id = txid_current()
        AND m.table_name = actual_table AND m.row_operation = actual_operation
        AND m.row_id = (actual_new->>'id')::BIGINT
        AND m.expected_old IS NOT DISTINCT FROM actual_old
        AND m.expected_new = actual_new
        AND m.local_tenant_key = (actual_new->>'tenant_id')::BIGINT
        AND m.local_version_key = (actual_new->>'version_id')::BIGINT
        AND v.operation_id = m.version_identity_operation_id
        AND v.local_tenant_key = m.local_tenant_key
        AND v.local_version_key = m.local_version_key
        AND v.operation_id::TEXT = m.owner_binding->>'versionIdentityOperationId'
        AND v.target_namespace = m.owner_binding->>'targetNamespace'
        AND v.canonical_tenant_id::TEXT = m.owner_binding->>'canonicalTenantId'
        AND v.canonical_version_id::TEXT = m.owner_binding->>'canonicalVersionId'
        AND v.intake_operation_id::TEXT = m.owner_binding->>'intakeOperationId'
        AND v.intake_request_id::TEXT = m.owner_binding->>'intakeRequestId'
        AND v.intake_request_digest = m.owner_binding->>'intakeRequestDigest'
        AND v.source_operation_id::TEXT = m.owner_binding->>'sourceOperationId'
        AND v.source_evidence_digest = m.owner_binding->>'sourceEvidenceDigest'
        AND v.intake_receipt_digest = m.owner_binding->>'intakeReceiptDigest'
        AND v.game_design_version_id = (m.owner_binding->>'gameDesignVersionId')::BIGINT
        AND v.canonical_tenant_id::TEXT = m.complete_binding->>'canonicalTenantId'
        AND v.canonical_version_id::TEXT = m.complete_binding->>'canonicalVersionId'
        AND m.operation_id::TEXT = m.complete_binding->>'commitId'
        AND o.target_namespace = v.target_namespace
        AND o.canonical_tenant_id = v.canonical_tenant_id
        AND o.local_tenant_key = v.local_tenant_key AND o.version_id = v.local_version_key
        AND o.owner_freeze_phase = 'OPEN' AND o.current_publication_fence IS NULL
    RETURNING m.row_id INTO consumed;
    RETURN consumed IS NOT NULL;
END;
$$;
REVOKE ALL ON FUNCTION world_consume_region_execution_manifest(TEXT, TEXT, JSONB, JSONB) FROM PUBLIC;

-- Retain the V24 deny boundary everywhere except an exact, single-use transition created by
-- the dedicated function. Moving a row out of a canonical tenant still enters this branch.
CREATE OR REPLACE FUNCTION world_claim_legacy_numeric_tenant_key()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    claimed_key BIGINT;
    old_row JSONB;
    new_row JSONB;
    canonical_old BOOLEAN := FALSE;
    canonical_new BOOLEAN := FALSE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_row := to_jsonb(OLD);
        SELECT EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_source_tenant_key_reservation
            WHERE tenant_key = OLD.tenant_id AND claim_kind = 'CANONICAL_AUTHORED_SOURCE')
            INTO canonical_old;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_row := to_jsonb(NEW);
        SELECT EXISTS (SELECT 1 FROM "${serviceSchema}".world_authored_source_tenant_key_reservation
            WHERE tenant_key = NEW.tenant_id AND claim_kind = 'CANONICAL_AUTHORED_SOURCE')
            INTO canonical_new;
    END IF;
    IF canonical_old OR canonical_new THEN
        IF TG_TABLE_SCHEMA = '${serviceSchema}'
            AND "${serviceSchema}".world_consume_region_execution_manifest(TG_TABLE_NAME, TG_OP, old_row, new_row)
        THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'World tenant key is reserved for a canonical authored source; no exact transaction execution manifest'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    INSERT INTO "${serviceSchema}".world_authored_source_tenant_key_reservation AS reservation
        (tenant_key, claim_kind) VALUES (NEW.tenant_id, 'LEGACY_NUMERIC')
    ON CONFLICT (tenant_key) DO UPDATE SET tenant_key = EXCLUDED.tenant_key
        WHERE reservation.claim_kind = 'LEGACY_NUMERIC'
    RETURNING reservation.tenant_key INTO claimed_key;
    IF claimed_key IS NULL THEN
        RAISE EXCEPTION 'World tenant key % is reserved for a canonical authored source', NEW.tenant_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- p_changes is the trusted component's typed, independently scope-validated complete World
-- subset. It grants no permission. Its tuples are rechecked against the retained full binding.
-- Neither a tenant allowlist nor a session flag can enable a guarded row write.
CREATE FUNCTION world_store_guarded_region_rows(p_owner JSONB, p_binding JSONB, p_changes JSONB)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    identity_row "${serviceSchema}".world_authored_version_identity%ROWTYPE;
    intake_row "${serviceSchema}".world_authored_source_intake%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    old_region "${serviceSchema}".region%ROWTYPE;
    new_region "${serviceSchema}".region%ROWTYPE;
    old_aggregate "${serviceSchema}".world_design_aggregate_epoch%ROWTYPE;
    new_aggregate "${serviceSchema}".world_design_aggregate_epoch%ROWTYPE;
    old_scope "${serviceSchema}".world_design_scope_epoch%ROWTYPE;
    new_scope "${serviceSchema}".world_design_scope_epoch%ROWTYPE;
    change JSONB;
    expected_units JSONB := '[]'::JSONB;
    supplied_units JSONB;
    expected_aggregate BIGINT;
    expected_scope BIGINT;
    aggregate_exists BOOLEAN;
    scope_exists BOOLEAN;
    affected INTEGER;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off'
        OR jsonb_typeof(p_changes) <> 'array' OR jsonb_array_length(p_changes) = 0
    THEN
        RAISE EXCEPTION 'Complete World region storage requires writable READ COMMITTED input'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT identity_row FROM "${serviceSchema}".world_authored_version_identity
        WHERE operation_id = (p_owner->>'versionIdentityOperationId')::UUID FOR SHARE;
    SELECT * INTO STRICT intake_row FROM "${serviceSchema}".world_authored_source_intake
        WHERE operation_id = identity_row.intake_operation_id;
    IF p_owner IS DISTINCT FROM jsonb_build_object(
        'targetNamespace', identity_row.target_namespace,
        'canonicalTenantId', identity_row.canonical_tenant_id,
        'canonicalVersionId', identity_row.canonical_version_id,
        'versionIdentityOperationId', identity_row.operation_id,
        'gameDesignVersionId', identity_row.game_design_version_id,
        'intakeRequestId', identity_row.intake_request_id,
        'intakeOperationId', identity_row.intake_operation_id,
        'intakeRequestDigest', identity_row.intake_request_digest,
        'sourceOperationId', identity_row.source_operation_id,
        'sourceEvidenceDigest', identity_row.source_evidence_digest,
        'intakeReceiptDigest', identity_row.intake_receipt_digest)
        OR p_binding->>'canonicalTenantId' IS DISTINCT FROM identity_row.canonical_tenant_id::TEXT
        OR p_binding->>'canonicalVersionId' IS DISTINCT FROM identity_row.canonical_version_id::TEXT
        OR p_binding->'target' IS DISTINCT FROM jsonb_build_object(
            'gameDesignVersionRowId', identity_row.game_design_version_id::TEXT,
            'gameDesignVersionTenantKey', intake_row.source_game_tenant_key,
            'sourceGameRowId', intake_row.source_game_row_id::TEXT,
            'sourceGameTenantKey', intake_row.source_game_tenant_key,
            'sourceProvenanceKind', intake_row.source_provenance_kind)
    THEN
        RAISE EXCEPTION 'Complete World region source binding differs from retained intake/V27'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT owner_row FROM "${serviceSchema}".world_design_publication_fence_owner
        WHERE target_namespace = identity_row.target_namespace
            AND canonical_tenant_id = identity_row.canonical_tenant_id
            AND local_tenant_key = identity_row.local_tenant_key
            AND version_id = identity_row.local_version_key FOR UPDATE;
    IF owner_row.owner_freeze_phase <> 'OPEN' OR owner_row.current_publication_fence IS NOT NULL THEN
        RAISE EXCEPTION 'World region storage requires the shared OPEN owner row'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_region_draft_execution_manifest
        WHERE transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'World complete region storage already has an execution manifest'
            USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(DISTINCT value->>'id') FROM jsonb_array_elements(p_changes))
        <> jsonb_array_length(p_changes) THEN
        RAISE EXCEPTION 'World complete region storage has duplicate region targets'
            USING ERRCODE = '23514';
    END IF;
    FOR change IN SELECT value FROM jsonb_array_elements(p_changes) LOOP
        expected_units := expected_units || jsonb_build_array(
            jsonb_build_object('owner', 'WORLD_MANAGEMENT', 'aggregateType', 'REGION',
                'aggregateId', change->>'id', 'scopeType', 'AGGREGATE', 'scopeId', change->>'id',
                'expectedEpoch', change->>'expectedAggregateEpoch'),
            jsonb_build_object('owner', 'WORLD_MANAGEMENT', 'aggregateType', 'REGION',
                'aggregateId', change->>'id', 'scopeType', 'REGION_SUBTREE', 'scopeId', change->>'id',
                'expectedEpoch', change->>'expectedScopeEpoch'));
    END LOOP;
    SELECT coalesce(jsonb_agg(value ORDER BY value->>'aggregateId', value->>'scopeType'), '[]'::JSONB)
        INTO supplied_units FROM jsonb_array_elements(p_binding->'affectedUnits')
        WHERE value->>'owner' = 'WORLD_MANAGEMENT';
    SELECT jsonb_agg(value ORDER BY value->>'aggregateId', value->>'scopeType')
        INTO expected_units FROM jsonb_array_elements(expected_units);
    IF supplied_units IS DISTINCT FROM expected_units THEN
        RAISE EXCEPTION 'World complete storage requires every exact REGION and REGION_SUBTREE tuple'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_array_elements(p_changes)
        WHERE (value->>'expectedAggregateEpoch')::BIGINT < 0
            OR (value->>'expectedAggregateEpoch')::BIGINT = 9223372036854775807
            OR (value->>'expectedScopeEpoch')::BIGINT < 0
            OR (value->>'expectedScopeEpoch')::BIGINT = 9223372036854775807) THEN
        RAISE EXCEPTION 'World epoch is not advanceable' USING ERRCODE = '23514';
    END IF;

    FOR change IN SELECT value FROM jsonb_array_elements(p_changes) LOOP
        expected_aggregate := (change->>'expectedAggregateEpoch')::BIGINT;
        expected_scope := (change->>'expectedScopeEpoch')::BIGINT;
        IF expected_aggregate < 0 OR expected_aggregate = 9223372036854775807
            OR expected_scope < 0 OR expected_scope = 9223372036854775807 THEN
            RAISE EXCEPTION 'World epoch is not advanceable' USING ERRCODE = '23514';
        END IF;
        SELECT * INTO STRICT old_region FROM "${serviceSchema}".region
            WHERE id = (change->>'id')::BIGINT AND tenant_id = identity_row.local_tenant_key
                AND version_id = identity_row.local_version_key FOR UPDATE;
        new_region := old_region;
        new_region.name := change->>'name';
        new_region.shard_id := (change->>'shardId')::INTEGER;
        new_region.weather := change->>'weather';
        new_region.generation_seed := (change->>'generationSeed')::BIGINT;
        new_region.generator_type := change->>'generatorType';
        new_region.generator_params := change->>'generatorParams';
        new_region.spacing_multiplier := (change->>'spacingMultiplier')::DOUBLE PRECISION;
        IF new_region.name IS NULL OR btrim(new_region.name) = '' OR new_region.shard_id < 0
            OR new_region.spacing_multiplier <= 0
            OR new_region.spacing_multiplier IN ('NaN'::DOUBLE PRECISION, 'Infinity'::DOUBLE PRECISION) THEN
            RAISE EXCEPTION 'World region payload is invalid' USING ERRCODE = '23514';
        END IF;
        INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
            txid_current(), (p_binding->>'commitId')::UUID, 'region', old_region.id, 'UPDATE',
            identity_row.operation_id, identity_row.local_tenant_key, identity_row.local_version_key,
            p_owner, p_binding, to_jsonb(old_region), to_jsonb(new_region));
        UPDATE "${serviceSchema}".region SET name = new_region.name, shard_id = new_region.shard_id,
            weather = new_region.weather, generation_seed = new_region.generation_seed,
            generator_type = new_region.generator_type, generator_params = new_region.generator_params,
            spacing_multiplier = new_region.spacing_multiplier
            WHERE id = old_region.id AND to_jsonb(region) = to_jsonb(old_region);
        GET DIAGNOSTICS affected = ROW_COUNT;
        IF affected <> 1 THEN
            RAISE EXCEPTION 'World region changed during complete storage' USING ERRCODE = '23514';
        END IF;

        SELECT * INTO old_aggregate FROM "${serviceSchema}".world_design_aggregate_epoch
            WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key
                AND aggregate_type = 'REGION' AND aggregate_id = old_region.id FOR UPDATE;
        aggregate_exists := FOUND;
        IF (aggregate_exists AND old_aggregate.draft_revision_epoch <> expected_aggregate)
            OR (NOT aggregate_exists AND expected_aggregate <> 0) THEN
            RAISE EXCEPTION 'DRAFT_WRITE_CONFLICT: REGION aggregate epoch' USING ERRCODE = '23514';
        END IF;
        IF aggregate_exists THEN
            new_aggregate := old_aggregate;
        ELSE
            new_aggregate.id := nextval('"${serviceSchema}".world_design_aggregate_epoch_id_seq');
            new_aggregate.tenant_id := identity_row.local_tenant_key;
            new_aggregate.version_id := identity_row.local_version_key;
            new_aggregate.aggregate_type := 'REGION';
            new_aggregate.aggregate_id := old_region.id;
        END IF;
        new_aggregate.draft_revision_epoch := expected_aggregate + 1;
        new_aggregate.updated_at := CURRENT_TIMESTAMP;
        INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
            txid_current(), (p_binding->>'commitId')::UUID, 'world_design_aggregate_epoch', new_aggregate.id,
            CASE WHEN aggregate_exists THEN 'UPDATE' ELSE 'INSERT' END,
            identity_row.operation_id, identity_row.local_tenant_key, identity_row.local_version_key,
            p_owner, p_binding, CASE WHEN aggregate_exists THEN to_jsonb(old_aggregate) END, to_jsonb(new_aggregate));
        IF aggregate_exists THEN
            UPDATE "${serviceSchema}".world_design_aggregate_epoch
                SET draft_revision_epoch = new_aggregate.draft_revision_epoch, updated_at = new_aggregate.updated_at
                WHERE id = old_aggregate.id AND draft_revision_epoch = expected_aggregate;
        ELSE
            INSERT INTO "${serviceSchema}".world_design_aggregate_epoch SELECT (new_aggregate).*;
        END IF;
        GET DIAGNOSTICS affected = ROW_COUNT;
        IF affected <> 1 THEN
            RAISE EXCEPTION 'DRAFT_WRITE_CONFLICT: REGION aggregate CAS' USING ERRCODE = '23514';
        END IF;

        SELECT * INTO old_scope FROM "${serviceSchema}".world_design_scope_epoch
            WHERE tenant_id = identity_row.local_tenant_key AND version_id = identity_row.local_version_key
                AND scope_type = 'REGION_SUBTREE' AND scope_id = old_region.id::TEXT FOR UPDATE;
        scope_exists := FOUND;
        IF (scope_exists AND old_scope.draft_scope_revision_epoch <> expected_scope)
            OR (NOT scope_exists AND expected_scope <> 0) THEN
            RAISE EXCEPTION 'DRAFT_WRITE_CONFLICT: REGION_SUBTREE epoch' USING ERRCODE = '23514';
        END IF;
        IF scope_exists THEN
            new_scope := old_scope;
        ELSE
            new_scope.id := nextval('"${serviceSchema}".world_design_scope_epoch_id_seq');
            new_scope.tenant_id := identity_row.local_tenant_key;
            new_scope.version_id := identity_row.local_version_key;
            new_scope.scope_type := 'REGION_SUBTREE';
            new_scope.scope_id := old_region.id::TEXT;
        END IF;
        new_scope.draft_scope_revision_epoch := expected_scope + 1;
        new_scope.updated_at := CURRENT_TIMESTAMP;
        INSERT INTO "${serviceSchema}".world_region_draft_execution_manifest VALUES (
            txid_current(), (p_binding->>'commitId')::UUID, 'world_design_scope_epoch', new_scope.id,
            CASE WHEN scope_exists THEN 'UPDATE' ELSE 'INSERT' END,
            identity_row.operation_id, identity_row.local_tenant_key, identity_row.local_version_key,
            p_owner, p_binding, CASE WHEN scope_exists THEN to_jsonb(old_scope) END, to_jsonb(new_scope));
        IF scope_exists THEN
            UPDATE "${serviceSchema}".world_design_scope_epoch
                SET draft_scope_revision_epoch = new_scope.draft_scope_revision_epoch, updated_at = new_scope.updated_at
                WHERE id = old_scope.id AND draft_scope_revision_epoch = expected_scope;
        ELSE
            INSERT INTO "${serviceSchema}".world_design_scope_epoch SELECT (new_scope).*;
        END IF;
        GET DIAGNOSTICS affected = ROW_COUNT;
        IF affected <> 1 THEN
            RAISE EXCEPTION 'DRAFT_WRITE_CONFLICT: REGION_SUBTREE CAS' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM "${serviceSchema}".world_region_draft_execution_manifest WHERE transaction_id = txid_current()) THEN
        RAISE EXCEPTION 'Complete World storage left an unconsumed execution manifest'
            USING ERRCODE = '23514';
    END IF;
END;
$$;
-- Explicit internal calls only; deployment roles must deliberately grant access when an
-- authenticated producer exists. Schema owners are the trusted database administration boundary.
REVOKE ALL ON FUNCTION world_store_guarded_region_rows(JSONB, JSONB, JSONB) FROM PUBLIC;
-- [jooq ignore stop]
