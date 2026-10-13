ALTER TABLE world_authored_version_identity
    ADD CONSTRAINT uq_world_authored_version_snapshot_binding UNIQUE (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        world_slug,
        canonical_version_id,
        game_design_version_id,
        local_version_key,
        intake_operation_id,
        intake_request_id,
        intake_request_digest,
        local_tenant_key,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest
    ),
    ADD CONSTRAINT uq_world_authored_version_snapshot_attempt_binding UNIQUE (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        canonical_version_id,
        game_design_version_id,
        local_version_key,
        intake_operation_id,
        intake_request_id,
        intake_request_digest,
        local_tenant_key,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest
    );

ALTER TABLE world_authored_source_intake
    ADD CONSTRAINT uq_world_authored_source_intake_snapshot_binding UNIQUE (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        local_tenant_key,
        intake_request_id,
        request_digest,
        source_operation_id,
        source_evidence_digest,
        receipt_digest
    );

ALTER TABLE world_design_publication_fence_attempt
    ADD COLUMN owner_binding_schema_version SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN canonical_version_id UUID,
    ADD COLUMN version_identity_operation_id UUID,
    ADD COLUMN game_design_version_id BIGINT,
    ADD COLUMN intake_request_digest VARCHAR(71),
    ADD CONSTRAINT ck_world_publication_attempt_owner_binding CHECK (
        (owner_binding_schema_version = 0
            AND canonical_version_id IS NULL
            AND version_identity_operation_id IS NULL
            AND game_design_version_id IS NULL
            AND intake_request_digest IS NULL)
        OR
        (owner_binding_schema_version = 1
            AND canonical_version_id IS NOT NULL
            AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND version_identity_operation_id IS NOT NULL
            AND version_identity_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND game_design_version_id IS NOT NULL
            AND game_design_version_id > 0
            AND intake_request_digest IS NOT NULL
            AND intake_request_digest ~ '^sha256:[0-9a-f]{64}$')
    ),
    ADD CONSTRAINT fk_world_publication_attempt_version_identity FOREIGN KEY (
        version_identity_operation_id,
        target_namespace,
        canonical_tenant_id,
        canonical_version_id,
        game_design_version_id,
        version_id,
        intake_operation_id,
        intake_request_id,
        intake_request_digest,
        local_tenant_key,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest
    ) REFERENCES world_authored_version_identity (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        canonical_version_id,
        game_design_version_id,
        local_version_key,
        intake_operation_id,
        intake_request_id,
        intake_request_digest,
        local_tenant_key,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest
    );

ALTER TABLE world_design_publication_fence_attempt
    ADD CONSTRAINT uq_world_publication_snapshot_binding UNIQUE (
        publication_fence,
        target_namespace,
        canonical_tenant_id,
        local_tenant_key,
        version_id,
        owner_binding_schema_version,
        canonical_version_id,
        version_identity_operation_id,
        game_design_version_id,
        intake_operation_id,
        intake_request_id,
        intake_request_digest,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest,
        publication_request_id,
        request_digest,
        version_state_epoch,
        publish_workflow_id,
        applied_commit_id,
        content_digest,
        digest_schema_version
    );

CREATE TABLE world_authored_graph_snapshot (
    snapshot_id UUID NOT NULL,
    capture_request_digest VARCHAR(64) NOT NULL,
    publication_fence UUID NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    version_identity_operation_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    game_design_version_id BIGINT NOT NULL,
    local_version_key BIGINT NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    owner_binding_schema_version SMALLINT NOT NULL,
    intake_operation_id UUID NOT NULL,
    intake_request_id UUID NOT NULL,
    intake_request_digest VARCHAR(71) NOT NULL,
    source_operation_id UUID NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL,
    intake_receipt_digest VARCHAR(71) NOT NULL,
    publication_request_id VARCHAR(256) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    version_state_epoch BIGINT NOT NULL,
    publish_workflow_id VARCHAR(537) NOT NULL,
    applied_commit_id VARCHAR(256) NOT NULL,
    content_digest VARCHAR(64) NOT NULL,
    digest_schema_version INTEGER NOT NULL,
    supplied_owned_affected_tuples_json TEXT NOT NULL,
    owner_revision_evidence_json TEXT NOT NULL,
    owner_commit_proof_status VARCHAR(32) NOT NULL,
    graph_bytes BYTEA NOT NULL,
    graph_sha256 VARCHAR(64) NOT NULL,
    captured_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_world_authored_graph_snapshot PRIMARY KEY (snapshot_id),
    CONSTRAINT uq_world_authored_graph_snapshot_fence UNIQUE (publication_fence),
    CONSTRAINT uq_world_authored_graph_snapshot_request UNIQUE (
        target_namespace, canonical_tenant_id, canonical_version_id, publication_request_id
    ),
    CONSTRAINT fk_world_authored_graph_snapshot_attempt FOREIGN KEY (
        publication_fence,
        target_namespace,
        canonical_tenant_id,
        local_tenant_key,
        local_version_key,
        owner_binding_schema_version,
        canonical_version_id,
        version_identity_operation_id,
        game_design_version_id,
        intake_operation_id,
        intake_request_id,
        intake_request_digest,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest,
        publication_request_id,
        request_digest,
        version_state_epoch,
        publish_workflow_id,
        applied_commit_id,
        content_digest,
        digest_schema_version
    ) REFERENCES world_design_publication_fence_attempt (
        publication_fence,
        target_namespace,
        canonical_tenant_id,
        local_tenant_key,
        version_id,
        owner_binding_schema_version,
        canonical_version_id,
        version_identity_operation_id,
        game_design_version_id,
        intake_operation_id,
        intake_request_id,
        intake_request_digest,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest,
        publication_request_id,
        request_digest,
        version_state_epoch,
        publish_workflow_id,
        applied_commit_id,
        content_digest,
        digest_schema_version
    ),
    CONSTRAINT fk_world_authored_graph_snapshot_version_identity FOREIGN KEY (
        version_identity_operation_id,
        target_namespace,
        canonical_tenant_id,
        world_slug,
        canonical_version_id,
        game_design_version_id,
        local_version_key,
        intake_operation_id,
        intake_request_id,
        intake_request_digest,
        local_tenant_key,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest
    ) REFERENCES world_authored_version_identity (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        world_slug,
        canonical_version_id,
        game_design_version_id,
        local_version_key,
        intake_operation_id,
        intake_request_id,
        intake_request_digest,
        local_tenant_key,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest
    ),
    CONSTRAINT fk_world_authored_graph_snapshot_intake FOREIGN KEY (
        intake_operation_id,
        target_namespace,
        canonical_tenant_id,
        local_tenant_key,
        intake_request_id,
        intake_request_digest,
        source_operation_id,
        source_evidence_digest,
        intake_receipt_digest
    ) REFERENCES world_authored_source_intake (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        local_tenant_key,
        intake_request_id,
        request_digest,
        source_operation_id,
        source_evidence_digest,
        receipt_digest
    ),
    CONSTRAINT ck_world_authored_graph_snapshot_scope CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND game_design_version_id > 0
        AND local_version_key > 0
        AND local_tenant_key > 0
        AND owner_binding_schema_version = 1
        AND version_state_epoch > 0
        AND digest_schema_version = 2
    ),
    CONSTRAINT ck_world_authored_graph_snapshot_ids CHECK (
        snapshot_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND publication_fence <> '00000000-0000-0000-0000-000000000000'::UUID
        AND version_identity_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT ck_world_authored_graph_snapshot_namespace CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
    ),
    CONSTRAINT ck_world_authored_graph_snapshot_source_digests CHECK (
        intake_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND
        source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND intake_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_world_authored_graph_snapshot_request CHECK (
        octet_length(publication_request_id) BETWEEN 1 AND 256
        AND position(':' IN publication_request_id) = 0
        AND request_digest ~ '^[0-9a-f]{64}$'
        AND capture_request_digest ~ '^[0-9a-f]{64}$'
        AND publish_workflow_id =
            'publish:' || canonical_tenant_id::TEXT || ':publish-request:' || publication_request_id
    ),
    CONSTRAINT ck_world_authored_graph_snapshot_checkpoint CHECK (
        octet_length(applied_commit_id) BETWEEN 1 AND 256
        AND content_digest ~ '^[0-9a-f]{64}$'
        AND graph_sha256 ~ '^[0-9a-f]{64}$'
        AND octet_length(graph_bytes) > 0
    ),
    CONSTRAINT ck_world_authored_graph_snapshot_proof CHECK (
        owner_commit_proof_status = 'CAPTURED_UNVERIFIED'
        AND octet_length(supplied_owned_affected_tuples_json) > 0
        AND octet_length(owner_revision_evidence_json) > 0
    )
);

-- [jooq ignore start]
CREATE FUNCTION world_reject_authored_graph_snapshot_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'World authored graph snapshot history is immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_world_authored_graph_snapshot_immutable
    BEFORE UPDATE OR DELETE ON world_authored_graph_snapshot
    FOR EACH ROW EXECUTE FUNCTION world_reject_authored_graph_snapshot_mutation();

CREATE TRIGGER trg_world_authored_graph_snapshot_no_truncate
    BEFORE TRUNCATE ON world_authored_graph_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_graph_snapshot_mutation();
-- [jooq ignore stop]
