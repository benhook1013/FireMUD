CREATE TABLE world_entity_spawn_binding (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    version_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    entity_template_type VARCHAR(32) NOT NULL,
    entity_template_id BIGINT NOT NULL,
    spawn_count INT NOT NULL DEFAULT 1,
    respawn_delay_seconds INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX idx_world_entity_spawn_binding_unique
    ON world_entity_spawn_binding (
        tenant_id,
        version_id,
        room_id,
        entity_template_type,
        entity_template_id
    );

CREATE INDEX idx_world_entity_spawn_binding_tenant_version
    ON world_entity_spawn_binding (tenant_id, version_id, id);
