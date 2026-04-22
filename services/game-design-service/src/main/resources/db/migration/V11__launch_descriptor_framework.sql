ALTER TABLE published_release_bundle
    ADD COLUMN generation_config_revision VARCHAR(128) NOT NULL DEFAULT 'genrev:legacy';

UPDATE published_release_bundle
SET generation_config_revision = CONCAT('genrev:', tenant_id, ':', version_id, ':', manifest_hash);

ALTER TABLE game_templates
    ADD COLUMN default_version_id BIGINT,
    ADD COLUMN default_script_patch_version VARCHAR(100),
    ADD COLUMN default_runtime_flags_json TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN template_reference_phase VARCHAR(32) NOT NULL DEFAULT 'ENFORCED';

CREATE TABLE launch_descriptor (
    id BIGSERIAL PRIMARY KEY,
    launch_descriptor_id VARCHAR(64) NOT NULL UNIQUE,
    tenant_id VARCHAR(36) NOT NULL,
    game_template_id BIGINT NOT NULL REFERENCES game_templates(id),
    control_plane_request_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    version_id BIGINT NOT NULL REFERENCES version(id),
    script_patch_version VARCHAR(100),
    runtime_flags_json TEXT NOT NULL,
    generation_config_revision VARCHAR(128) NOT NULL,
    version_state_epoch BIGINT NOT NULL,
    release_bundle_id BIGINT NOT NULL REFERENCES published_release_bundle(id),
    published_release_bundle_ref VARCHAR(128) NOT NULL,
    remap_set_id VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_launch_descriptor_request UNIQUE (tenant_id, game_template_id, control_plane_request_id)
);

CREATE INDEX idx_launch_descriptor_tenant_template
    ON launch_descriptor(tenant_id, game_template_id);
