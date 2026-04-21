CREATE TABLE world_design_revision_ledger (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    commit_id VARCHAR(100) NOT NULL,
    revision_id VARCHAR(100) NOT NULL,
    operation_type VARCHAR(50) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    requested_aggregate_id VARCHAR(100) NOT NULL DEFAULT '',
    applied_aggregate_id BIGINT NOT NULL,
    result VARCHAR(50) NOT NULL,
    aggregate_epoch_after BIGINT NOT NULL,
    scope_epoch_after BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_world_design_revision UNIQUE (
        tenant_id,
        version_id,
        commit_id,
        revision_id,
        operation_type,
        aggregate_type,
        requested_aggregate_id
    )
);

CREATE TABLE world_design_aggregate_epoch (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    draft_revision_epoch BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_world_design_aggregate_epoch UNIQUE (
        tenant_id,
        version_id,
        aggregate_type,
        aggregate_id
    )
);

CREATE TABLE world_design_scope_epoch (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    scope_type VARCHAR(50) NOT NULL,
    scope_id VARCHAR(100) NOT NULL,
    draft_scope_revision_epoch BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_world_design_scope_epoch UNIQUE (
        tenant_id,
        version_id,
        scope_type,
        scope_id
    )
);

CREATE INDEX idx_world_design_revision_lookup
    ON world_design_revision_ledger (tenant_id, version_id, commit_id, revision_id);

CREATE INDEX idx_world_design_aggregate_epoch_lookup
    ON world_design_aggregate_epoch (tenant_id, version_id, aggregate_type, aggregate_id);

CREATE INDEX idx_world_design_scope_epoch_lookup
    ON world_design_scope_epoch (tenant_id, version_id, scope_type, scope_id);
