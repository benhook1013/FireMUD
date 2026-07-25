CREATE TABLE version_asset (
    tenant_id VARCHAR(36) NOT NULL,
    version_id BIGINT NOT NULL REFERENCES version(id),
    asset_id BIGINT NOT NULL REFERENCES game_assets(id),
    usage_type VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_version_asset_tenant_version_asset UNIQUE (tenant_id, version_id, asset_id)
);

CREATE INDEX idx_version_asset_tenant_version
    ON version_asset(tenant_id, version_id);

CREATE INDEX idx_version_asset_asset_id
    ON version_asset(asset_id);
