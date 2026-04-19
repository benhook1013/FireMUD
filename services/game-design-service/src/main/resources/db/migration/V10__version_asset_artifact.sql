CREATE TABLE version_asset_artifact (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL,
    artifact_state VARCHAR(32) NOT NULL,
    state_epoch BIGINT NOT NULL,
    manifest_hash VARCHAR(128),
    last_workflow_id VARCHAR(64),
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(512),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_version_asset_artifact_tenant_version UNIQUE (tenant_id, version_id)
);

CREATE INDEX idx_version_asset_artifact_tenant_id
    ON version_asset_artifact(tenant_id);
