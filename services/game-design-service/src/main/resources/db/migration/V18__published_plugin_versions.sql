CREATE TABLE published_plugin_versions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    plugin_id VARCHAR(128) NOT NULL,
    plugin_version_id VARCHAR(128) NOT NULL,
    base_version_id BIGINT NOT NULL,
    publication_state VARCHAR(32) NOT NULL,
    ability_schema_digest VARCHAR(256) NOT NULL,
    bundle_digest VARCHAR(256) NOT NULL,
    manifest_schema_version INTEGER NOT NULL,
    distribution_manifest_hash VARCHAR(256),
    distribution_manifest_path VARCHAR(512),
    notes VARCHAR(2000),
    last_changed_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_published_plugin_versions_identity
        UNIQUE (tenant_id, plugin_id, plugin_version_id)
);

CREATE INDEX idx_published_plugin_versions_tenant_base
    ON published_plugin_versions(tenant_id, base_version_id);
