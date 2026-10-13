ALTER TABLE world_authored_source_intake
    ADD CONSTRAINT uq_world_authored_source_intake_publication_binding UNIQUE (
        operation_id,
        target_namespace,
        canonical_tenant_id,
        local_tenant_key,
        intake_request_id,
        source_operation_id,
        source_evidence_digest,
        receipt_digest
    );

CREATE TABLE world_design_publication_fence_attempt (
    publication_fence UUID NOT NULL,
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    intake_operation_id UUID NOT NULL,
    intake_request_id UUID NOT NULL,
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
    CONSTRAINT pk_world_design_publication_fence_attempt
        PRIMARY KEY (publication_fence),
    CONSTRAINT uq_world_design_publication_attempt_scope_request
        UNIQUE (target_namespace, canonical_tenant_id, version_id, publication_request_id),
    CONSTRAINT uq_world_design_publication_attempt_owner_fence
        UNIQUE (
            publication_fence,
            target_namespace,
            canonical_tenant_id,
            local_tenant_key,
            version_id
        ),
    CONSTRAINT fk_world_design_publication_attempt_tenant_association
        FOREIGN KEY (target_namespace, canonical_tenant_id, local_tenant_key)
        REFERENCES world_authored_source_tenant_association
            (target_namespace, canonical_tenant_id, local_tenant_key),
    CONSTRAINT fk_world_design_publication_attempt_intake
        FOREIGN KEY (
            intake_operation_id,
            target_namespace,
            canonical_tenant_id,
            local_tenant_key,
            intake_request_id,
            source_operation_id,
            source_evidence_digest,
            intake_receipt_digest
        )
        REFERENCES world_authored_source_intake (
            operation_id,
            target_namespace,
            canonical_tenant_id,
            local_tenant_key,
            intake_request_id,
            source_operation_id,
            source_evidence_digest,
            receipt_digest
        ),
    CONSTRAINT ck_world_design_publication_attempt_scope
        CHECK (version_id > 0 AND version_state_epoch > 0 AND digest_schema_version > 0),
    CONSTRAINT ck_world_design_publication_attempt_namespace
        CHECK (
            octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        ),
    CONSTRAINT ck_world_design_publication_attempt_ids
        CHECK (
            publication_fence <> '00000000-0000-0000-0000-000000000000'::UUID
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND intake_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND local_tenant_key > 0
        ),
    CONSTRAINT ck_world_design_publication_attempt_source_digest
        CHECK (source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_world_design_publication_attempt_receipt_digest
        CHECK (intake_receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_world_design_publication_attempt_request
        CHECK (
            octet_length(publication_request_id) BETWEEN 1 AND 256
            AND position(':' IN publication_request_id) = 0
            AND octet_length(request_digest) = 64
            AND request_digest ~ '^[0-9a-f]{64}$'
            AND publish_workflow_id =
                'publish:' || canonical_tenant_id::TEXT || ':publish-request:' || publication_request_id
            AND octet_length(publish_workflow_id) <= 537
        ),
    CONSTRAINT ck_world_design_publication_attempt_checkpoint
        CHECK (
            octet_length(applied_commit_id) BETWEEN 1 AND 256
            AND content_digest ~ '^[0-9a-f]{64}$'
        )
);

CREATE TABLE world_design_publication_fence_owner (
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    local_tenant_key BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    owner_freeze_phase VARCHAR(32) NOT NULL,
    current_publication_fence UUID,
    CONSTRAINT pk_world_design_publication_fence_owner
        PRIMARY KEY (local_tenant_key, version_id),
    CONSTRAINT uq_world_design_publication_fence_owner_scope
        UNIQUE (target_namespace, canonical_tenant_id, version_id),
    CONSTRAINT fk_world_design_publication_owner_tenant_association
        FOREIGN KEY (target_namespace, canonical_tenant_id, local_tenant_key)
        REFERENCES world_authored_source_tenant_association
            (target_namespace, canonical_tenant_id, local_tenant_key),
    CONSTRAINT fk_world_design_publication_owner_current_attempt
        FOREIGN KEY (
            current_publication_fence,
            target_namespace,
            canonical_tenant_id,
            local_tenant_key,
            version_id
        )
        REFERENCES world_design_publication_fence_attempt (
            publication_fence,
            target_namespace,
            canonical_tenant_id,
            local_tenant_key,
            version_id
        ),
    CONSTRAINT ck_world_design_publication_fence_owner_scope
        CHECK (version_id > 0 AND local_tenant_key > 0),
    CONSTRAINT ck_world_design_publication_fence_owner_ids
        CHECK (
            canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
        ),
    CONSTRAINT ck_world_design_publication_fence_owner_namespace
        CHECK (
            octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        ),
    CONSTRAINT ck_world_design_publication_fence_owner_phase
        CHECK (
            (owner_freeze_phase = 'OPEN' AND current_publication_fence IS NULL)
            OR
            (owner_freeze_phase IN (
                'FROZEN',
                'PUBLISHED',
                'ABORTED',
                'RECONCILIATION_REQUIRED'
            ) AND current_publication_fence IS NOT NULL)
        )
);

-- [jooq ignore start]
CREATE FUNCTION world_reject_publication_attempt_history_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'World publication-fence attempt history is immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION world_protect_publication_fence_owner()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'World publication-fence owner rows cannot be deleted'
            USING ERRCODE = '55000';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.owner_freeze_phase = 'OPEN' AND NEW.current_publication_fence IS NULL THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'World publication-fence owner rows must be created OPEN without a fence'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.target_namespace IS NOT DISTINCT FROM NEW.target_namespace
        AND OLD.canonical_tenant_id IS NOT DISTINCT FROM NEW.canonical_tenant_id
        AND OLD.local_tenant_key IS NOT DISTINCT FROM NEW.local_tenant_key
        AND OLD.version_id IS NOT DISTINCT FROM NEW.version_id
        AND OLD.owner_freeze_phase = 'OPEN'
        AND NEW.owner_freeze_phase = 'FROZEN'
        AND OLD.current_publication_fence IS NULL
        AND NEW.current_publication_fence IS NOT NULL
    THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'World publication-fence owner binding or phase transition is immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_world_publication_attempt_immutable
    BEFORE UPDATE OR DELETE ON world_design_publication_fence_attempt
    FOR EACH ROW EXECUTE FUNCTION world_reject_publication_attempt_history_mutation();

CREATE TRIGGER trg_world_publication_attempt_no_truncate
    BEFORE TRUNCATE ON world_design_publication_fence_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_publication_attempt_history_mutation();

CREATE TRIGGER trg_world_publication_owner_protect
    BEFORE INSERT OR UPDATE OR DELETE ON world_design_publication_fence_owner
    FOR EACH ROW EXECUTE FUNCTION world_protect_publication_fence_owner();

CREATE TRIGGER trg_world_publication_owner_no_truncate
    BEFORE TRUNCATE ON world_design_publication_fence_owner
    FOR EACH STATEMENT EXECUTE FUNCTION world_reject_publication_attempt_history_mutation();
-- [jooq ignore stop]
