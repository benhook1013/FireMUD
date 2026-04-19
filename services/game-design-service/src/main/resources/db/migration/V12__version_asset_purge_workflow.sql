ALTER TABLE version_asset_artifact
    ADD COLUMN exported_manifest_asset_keys_json TEXT NOT NULL DEFAULT '[]';

CREATE TABLE version_asset_purge_workflow (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL,
    purge_workflow_id VARCHAR(64) NOT NULL UNIQUE,
    workflow_status VARCHAR(32) NOT NULL,
    started_from_state_epoch BIGINT NOT NULL,
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(512),
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_version_asset_purge_workflow_tenant_version
    ON version_asset_purge_workflow(tenant_id, version_id);
