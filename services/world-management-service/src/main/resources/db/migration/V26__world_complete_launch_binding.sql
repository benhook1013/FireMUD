CREATE TABLE world_complete_launch_binding (
    binding_operation_id UUID NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    canonical_version_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    control_plane_request_id TEXT NOT NULL,
    intake_operation_id UUID NOT NULL,
    intake_request_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    source_operation_id UUID NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL,
    intake_receipt_digest VARCHAR(71) NOT NULL,
    descriptor_request_digest VARCHAR(71) NOT NULL,
    descriptor_result_digest VARCHAR(71) NOT NULL,
    release_attestation_digest VARCHAR(71) NOT NULL,
    descriptor_json TEXT NOT NULL,
    release_attestation_json TEXT NOT NULL,
    CONSTRAINT pk_world_complete_launch_binding PRIMARY KEY (binding_operation_id),
    CONSTRAINT fk_world_complete_launch_binding_source
        FOREIGN KEY (
            intake_operation_id, target_namespace, canonical_tenant_id, local_tenant_key,
            intake_request_id, source_operation_id, source_evidence_digest, intake_receipt_digest
        ) REFERENCES world_authored_source_intake (
            operation_id, target_namespace, canonical_tenant_id, local_tenant_key,
            intake_request_id, source_operation_id, source_evidence_digest, receipt_digest
        ),
    CONSTRAINT ck_world_complete_launch_binding_ids CHECK (
        binding_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND local_tenant_key > 0
    ),
    CONSTRAINT ck_world_complete_launch_binding_namespace CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
    ),
    CONSTRAINT ck_world_complete_launch_binding_world CHECK (
        world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT ck_world_complete_launch_binding_request CHECK (
        length(btrim(control_plane_request_id)) > 0
    ),
    CONSTRAINT ck_world_complete_launch_binding_digests CHECK (
        source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND intake_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND descriptor_result_digest ~ '^sha256:[0-9a-f]{64}$'
        AND release_attestation_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_world_complete_launch_binding_components CHECK (
        length(descriptor_json) > 0 AND length(release_attestation_json) > 0
    )
);

-- [jooq ignore start]
-- The DDL export backend cannot index TEXT. PostgreSQL still enforces this exact
-- unbounded request identity; the runtime repository does not use generated keys.
ALTER TABLE world_complete_launch_binding
    ADD CONSTRAINT uq_world_complete_launch_binding_request
    UNIQUE (target_namespace, canonical_tenant_id, control_plane_request_id);

CREATE TRIGGER trg_world_complete_launch_binding_immutable
    BEFORE UPDATE OR DELETE ON world_complete_launch_binding
    FOR EACH ROW EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE TRIGGER trg_world_complete_launch_binding_no_truncate
    BEFORE TRUNCATE ON world_complete_launch_binding
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();
-- [jooq ignore stop]
