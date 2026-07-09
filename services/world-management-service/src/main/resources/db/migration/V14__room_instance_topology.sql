CREATE TABLE room_instance (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    room_instance_row_id BIGINT NOT NULL,
    template_room_id BIGINT NOT NULL REFERENCES room(id),
    region_instance_id BIGINT NOT NULL REFERENCES region_instance(id),
    name VARCHAR(100) NOT NULL,
    description TEXT,
    name_localized_variants_json TEXT,
    description_localized_variants_json TEXT,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_room_instance_tenant_game_room UNIQUE (tenant_id, game_instance_id, room_instance_row_id)
);

CREATE TABLE room_instance_exit (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    from_room_instance_id BIGINT NOT NULL REFERENCES room_instance(id),
    to_room_instance_id BIGINT NOT NULL REFERENCES room_instance(id),
    direction VARCHAR(32) NOT NULL,
    cost INT NOT NULL DEFAULT 1,
    version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_room_instance_tenant_game ON room_instance(tenant_id, game_instance_id);
CREATE INDEX idx_room_instance_exit_from ON room_instance_exit(from_room_instance_id);
