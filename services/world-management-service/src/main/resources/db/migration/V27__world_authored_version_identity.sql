CREATE TABLE world_authored_version_identity (
    operation_id UUID NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    canonical_version_id UUID NOT NULL,
    game_design_version_id BIGINT NOT NULL,
    local_version_key BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL,
    intake_operation_id UUID NOT NULL,
    intake_request_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    intake_request_digest VARCHAR(71) NOT NULL,
    source_operation_id UUID NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL,
    intake_receipt_digest VARCHAR(71) NOT NULL,
    version_state_schema_version SMALLINT NOT NULL,
    version_state_read_request_id UUID NOT NULL,
    version_state_state VARCHAR(32) NOT NULL,
    version_state_epoch BIGINT NOT NULL,
    version_state_evidence_digest VARCHAR(71) NOT NULL,
    version_state_evidence_json TEXT NOT NULL,
    CONSTRAINT pk_world_authored_version_identity PRIMARY KEY (operation_id),
    CONSTRAINT uq_world_authored_version_identity_local_key UNIQUE (local_version_key),
    CONSTRAINT uq_world_authored_version_identity_canonical UNIQUE (
        target_namespace, canonical_tenant_id, world_slug, canonical_version_id
    ),
    CONSTRAINT uq_world_authored_version_identity_game_design_version UNIQUE (
        target_namespace, canonical_tenant_id, world_slug, game_design_version_id
    ),
    CONSTRAINT fk_world_authored_version_identity_intake FOREIGN KEY (
        intake_operation_id, target_namespace, canonical_tenant_id, local_tenant_key,
        intake_request_id, source_operation_id, source_evidence_digest, intake_receipt_digest
    ) REFERENCES world_authored_source_intake (
        operation_id, target_namespace, canonical_tenant_id, local_tenant_key,
        intake_request_id, source_operation_id, source_evidence_digest, receipt_digest
    ),
    CONSTRAINT ck_world_authored_version_identity_scope CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        AND octet_length(world_slug) BETWEEN 1 AND 120
    ),
    CONSTRAINT ck_world_authored_version_identity_counters CHECK (
        game_design_version_id > 0
        AND local_version_key > 0
        AND local_tenant_key > 0
        AND version_state_epoch > 0
        AND version_state_schema_version = 1
    ),
    CONSTRAINT ck_world_authored_version_identity_ids CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND version_state_read_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT ck_world_authored_version_identity_state CHECK (
        version_state_state IN ('DRAFT', 'PUBLISHED', 'ACTIVE')
    ),
    CONSTRAINT ck_world_authored_version_identity_digests CHECK (
        intake_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
        AND intake_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND version_state_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_world_authored_version_identity_json CHECK (
        octet_length(version_state_evidence_json) > 0
    )
);

-- [jooq ignore start]
CREATE TRIGGER trg_world_authored_version_identity_immutable
    BEFORE UPDATE OR DELETE ON world_authored_version_identity
    FOR EACH ROW EXECUTE FUNCTION world_reject_authored_source_history_mutation();

CREATE TRIGGER trg_world_authored_version_identity_no_truncate
    BEFORE TRUNCATE ON world_authored_version_identity
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_authored_source_history_mutation();
-- [jooq ignore stop]
