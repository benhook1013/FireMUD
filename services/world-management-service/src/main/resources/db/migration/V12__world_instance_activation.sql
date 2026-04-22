CREATE TABLE world_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    game_template_id BIGINT NOT NULL,
    control_plane_request_id VARCHAR(128) NOT NULL,
    launch_descriptor_id VARCHAR(64) NOT NULL,
    version_id BIGINT NOT NULL,
    script_patch_version VARCHAR(100),
    runtime_flags_json TEXT,
    generation_config_revision VARCHAR(128) NOT NULL,
    release_bundle_id BIGINT NOT NULL,
    published_release_bundle_ref VARCHAR(128) NOT NULL,
    version_state_epoch BIGINT NOT NULL,
    lifecycle_epoch BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_world_instance_tenant_game_instance UNIQUE (tenant_id, game_instance_id)
);

CREATE TABLE region_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    world_instance_id BIGINT NOT NULL REFERENCES world_instance(id) ON DELETE CASCADE,
    shard_id INTEGER NOT NULL DEFAULT 0,
    name VARCHAR(100) NOT NULL,
    weather VARCHAR(50),
    generation_seed BIGINT NOT NULL DEFAULT 0,
    generator_type VARCHAR(50),
    generator_params TEXT,
    spacing_multiplier DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    version INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_world_instance_tenant_status
    ON world_instance(tenant_id, status);

CREATE INDEX idx_region_instance_tenant_game_instance
    ON region_instance(tenant_id, game_instance_id);
