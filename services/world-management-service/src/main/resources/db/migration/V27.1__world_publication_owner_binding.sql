-- This moves the publication-binding clauses from the deferred V28 graph-snapshot migration:
-- uq_world_authored_version_snapshot_attempt_binding, the five attempt binding columns,
-- ck_world_publication_attempt_owner_binding, and
-- fk_world_publication_attempt_version_identity. Reconcile those clauses when V28 is integrated.
ALTER TABLE world_authored_version_identity
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
