-- Immutable component capture only. The V25 checkpoint is retained, never redefined as graph hash.
CREATE TABLE world_canonical_frozen_topology (
    capture_id UUID PRIMARY KEY CHECK (capture_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    publication_fence UUID NOT NULL UNIQUE REFERENCES world_design_publication_fence_attempt(publication_fence),
    request_id UUID NOT NULL,
    commit_id UUID NOT NULL,
    version_identity_operation_id UUID NOT NULL,
    freeze_request_json TEXT NOT NULL,
    owner_binding_json TEXT NOT NULL,
    binding_json TEXT NOT NULL,
    binding_digest VARCHAR(71) NOT NULL CHECK (binding_digest ~ '^sha256:[0-9a-f]{64}$'),
    identity_json TEXT NOT NULL,
    intake_json TEXT NOT NULL,
    graph_bytes BYTEA NOT NULL CHECK (octet_length(graph_bytes) > 0),
    graph_sha256 VARCHAR(64) NOT NULL CHECK (graph_sha256 ~ '^[0-9a-f]{64}$'),
    storage_result_bytes BYTEA NOT NULL CHECK (octet_length(storage_result_bytes) > 0),
    result_bytes BYTEA NOT NULL CHECK (octet_length(result_bytes) > 0),
    capture_status VARCHAR(32) NOT NULL CHECK (capture_status = 'CAPTURED_UNVERIFIED'),
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (request_id) REFERENCES world_topology_draft_commit(request_id),
    FOREIGN KEY (commit_id) REFERENCES world_topology_draft_commit(commit_id),
    FOREIGN KEY (version_identity_operation_id) REFERENCES world_authored_version_identity(operation_id)
);

-- [jooq ignore start]
CREATE FUNCTION world_check_canonical_frozen_capture() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog, "${serviceSchema}"
AS $$
DECLARE
    commit_row "${serviceSchema}".world_topology_draft_commit%ROWTYPE;
    attempt "${serviceSchema}".world_design_publication_fence_attempt%ROWTYPE;
    owner_row "${serviceSchema}".world_design_publication_fence_owner%ROWTYPE;
    freeze JSONB;
    identity TEXT;
    intake TEXT;
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed'
        OR current_setting('transaction_read_only') <> 'off' THEN
        RAISE EXCEPTION 'Canonical frozen capture requires writable READ COMMITTED' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT commit_row FROM "${serviceSchema}".world_topology_draft_commit
        WHERE request_id=NEW.request_id AND commit_id=NEW.commit_id
            AND version_identity_operation_id=NEW.version_identity_operation_id;
    SELECT * INTO STRICT owner_row FROM "${serviceSchema}".world_design_publication_fence_owner
        WHERE local_tenant_key=commit_row.local_tenant_key AND version_id=commit_row.local_version_key FOR UPDATE;
    SELECT * INTO STRICT attempt FROM "${serviceSchema}".world_design_publication_fence_attempt
        WHERE publication_fence=NEW.publication_fence;
    SELECT to_jsonb(v)::text, to_jsonb(i)::text INTO STRICT identity, intake
        FROM "${serviceSchema}".world_authored_version_identity v
        JOIN "${serviceSchema}".world_authored_source_intake i ON i.operation_id=v.intake_operation_id
        WHERE v.operation_id=NEW.version_identity_operation_id;
    freeze := NEW.freeze_request_json::jsonb;
    IF owner_row.owner_freeze_phase <> 'FROZEN'
        OR owner_row.current_publication_fence IS DISTINCT FROM NEW.publication_fence
        OR attempt.owner_binding_schema_version IS DISTINCT FROM 1
        OR attempt.local_tenant_key IS DISTINCT FROM commit_row.local_tenant_key
        OR attempt.version_id IS DISTINCT FROM commit_row.local_version_key
        OR attempt.version_identity_operation_id IS DISTINCT FROM NEW.version_identity_operation_id
        OR attempt.canonical_tenant_id IS DISTINCT FROM commit_row.canonical_tenant_id
        OR attempt.canonical_version_id IS DISTINCT FROM commit_row.canonical_version_id
        OR attempt.target_namespace IS DISTINCT FROM commit_row.target_namespace
        OR attempt.applied_commit_id IS DISTINCT FROM NEW.commit_id::text
        OR freeze->>'targetNamespace' IS DISTINCT FROM attempt.target_namespace
        OR freeze->>'canonicalTenantId' IS DISTINCT FROM attempt.canonical_tenant_id::text
        OR freeze->>'canonicalVersionId' IS DISTINCT FROM attempt.canonical_version_id::text
        OR freeze->>'intakeRequestId' IS DISTINCT FROM attempt.intake_request_id::text
        OR freeze->>'publicationFence' IS DISTINCT FROM attempt.publication_fence::text
        OR freeze->>'publicationRequestId' IS DISTINCT FROM attempt.publication_request_id
        OR freeze->>'requestDigest' IS DISTINCT FROM attempt.request_digest
        OR freeze->>'versionStateEpoch' IS DISTINCT FROM attempt.version_state_epoch::text
        OR freeze->>'publishWorkflowId' IS DISTINCT FROM attempt.publish_workflow_id
        OR freeze->>'appliedCommitId' IS DISTINCT FROM attempt.applied_commit_id
        OR freeze->>'contentDigest' IS DISTINCT FROM attempt.content_digest
        OR freeze->>'digestSchemaVersion' IS DISTINCT FROM attempt.digest_schema_version::text
        OR NEW.owner_binding_json IS DISTINCT FROM commit_row.owner_binding_json
        OR NEW.binding_json IS DISTINCT FROM commit_row.binding_json
        OR NEW.binding_digest IS DISTINCT FROM commit_row.binding_digest
        OR NEW.graph_bytes IS DISTINCT FROM commit_row.graph_bytes
        OR NEW.graph_sha256 IS DISTINCT FROM commit_row.graph_sha256
        OR NEW.storage_result_bytes IS DISTINCT FROM commit_row.result_bytes
        OR NEW.identity_json IS DISTINCT FROM identity OR NEW.intake_json IS DISTINCT FROM intake THEN
        RAISE EXCEPTION 'Canonical frozen capture differs from exact original storage and FROZEN checkpoint' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_world_canonical_frozen_insert BEFORE INSERT
    ON world_canonical_frozen_topology FOR EACH ROW
    EXECUTE FUNCTION world_check_canonical_frozen_capture();
CREATE TRIGGER trg_world_canonical_frozen_immutable BEFORE UPDATE OR DELETE
    ON world_canonical_frozen_topology FOR EACH ROW
    EXECUTE FUNCTION world_reject_authored_source_history_mutation();
CREATE TRIGGER trg_world_canonical_frozen_no_truncate BEFORE TRUNCATE
    ON world_canonical_frozen_topology FOR EACH STATEMENT
    EXECUTE FUNCTION world_reject_authored_source_history_mutation();
-- [jooq ignore stop]
