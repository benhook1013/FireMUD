CREATE TABLE gameplay_admission_pointer (
    id BIGSERIAL PRIMARY KEY,
    world_slug VARCHAR(120) NOT NULL,
    world_display_name VARCHAR(200) NOT NULL,
    realm_slug VARCHAR(120) NOT NULL,
    realm_display_name VARCHAR(200) NOT NULL,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    pointer_version BIGINT NOT NULL,
    visible BOOLEAN NOT NULL,
    requires_character_selection BOOLEAN NOT NULL,
    state_scope VARCHAR(32) NOT NULL,
    character_creation_policy VARCHAR(32) NOT NULL,
    last_updated_by VARCHAR(200) NOT NULL,
    last_update_reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_gameplay_admission_pointer_world_realm
    ON gameplay_admission_pointer(world_slug, realm_slug);

CREATE INDEX idx_gameplay_admission_pointer_tenant_instance
    ON gameplay_admission_pointer(tenant_id, game_instance_id);

CREATE TABLE gameplay_admission_pointer_event (
    id BIGSERIAL PRIMARY KEY,
    world_slug VARCHAR(120) NOT NULL,
    realm_slug VARCHAR(120) NOT NULL,
    world_display_name VARCHAR(200) NOT NULL,
    realm_display_name VARCHAR(200) NOT NULL,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    pointer_version BIGINT NOT NULL,
    visible BOOLEAN NOT NULL,
    requires_character_selection BOOLEAN NOT NULL,
    state_scope VARCHAR(32) NOT NULL,
    character_creation_policy VARCHAR(32) NOT NULL,
    actor_principal VARCHAR(200) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    control_plane_request_id VARCHAR(120) NOT NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_gameplay_admission_pointer_event_world_realm
    ON gameplay_admission_pointer_event(world_slug, realm_slug, occurred_at DESC);
