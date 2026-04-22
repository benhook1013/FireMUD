CREATE TABLE version_template_remap_set (
    id BIGSERIAL PRIMARY KEY,
    remap_set_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id VARCHAR(36) NOT NULL,
    source_version_id BIGINT NOT NULL REFERENCES version(id),
    target_version_id BIGINT NOT NULL REFERENCES version(id),
    status VARCHAR(32) NOT NULL,
    created_reason VARCHAR(500) NOT NULL,
    approval_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_version_template_remap_set_version_pair
    ON version_template_remap_set(tenant_id, source_version_id, target_version_id, status);

ALTER TABLE launch_descriptor
    ADD COLUMN IF NOT EXISTS remap_set_id VARCHAR(64);

CREATE TABLE version_template_remap_entry (
    id BIGSERIAL PRIMARY KEY,
    remap_set_pk BIGINT NOT NULL REFERENCES version_template_remap_set(id) ON DELETE CASCADE,
    mapping_domain VARCHAR(64) NOT NULL,
    mapping_type VARCHAR(64) NOT NULL,
    source_template_key VARCHAR(128) NOT NULL,
    target_template_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_version_template_remap_entry UNIQUE (remap_set_pk, mapping_domain, mapping_type, source_template_key)
);
