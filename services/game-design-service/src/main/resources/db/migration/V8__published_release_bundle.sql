CREATE TABLE published_release_bundle (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL REFERENCES version(id),
    version_number INT NOT NULL,
    attestation_schema_version VARCHAR(16) NOT NULL,
    publish_workflow_id VARCHAR(64) NOT NULL,
    manifest_hash VARCHAR(128) NOT NULL,
    required_manifest_asset_keys_json TEXT NOT NULL,
    script_only BOOLEAN NOT NULL DEFAULT FALSE,
    script_patch_version VARCHAR(100),
    published_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_published_release_bundle_tenant_version UNIQUE (tenant_id, version_id)
);

CREATE INDEX idx_published_release_bundle_tenant_id
    ON published_release_bundle(tenant_id);
