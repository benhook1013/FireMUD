CREATE TABLE container_instances (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT REFERENCES characters(id),
    equipment_slot VARCHAR(64),
    game_instance_id VARCHAR(255),
    room_instance_id VARCHAR(255),
    item_id BIGINT NOT NULL REFERENCES items(id),
    version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_container_instances_tenant_character
    ON container_instances(tenant_id, character_id);

CREATE INDEX idx_container_instances_room
    ON container_instances(tenant_id, game_instance_id, room_instance_id);

CREATE INDEX idx_container_instances_item
    ON container_instances(item_id);

CREATE UNIQUE INDEX ux_container_instances_inventory
    ON container_instances(tenant_id, character_id, item_id)
    WHERE character_id IS NOT NULL
      AND equipment_slot IS NULL
      AND game_instance_id IS NULL
      AND room_instance_id IS NULL;

CREATE UNIQUE INDEX ux_container_instances_equipment
    ON container_instances(tenant_id, character_id, equipment_slot, item_id)
    WHERE character_id IS NOT NULL
      AND equipment_slot IS NOT NULL
      AND game_instance_id IS NULL
      AND room_instance_id IS NULL;

CREATE UNIQUE INDEX ux_container_instances_room
    ON container_instances(tenant_id, game_instance_id, room_instance_id, item_id)
    WHERE character_id IS NULL
      AND equipment_slot IS NULL
      AND game_instance_id IS NOT NULL
      AND room_instance_id IS NOT NULL;

DROP TABLE container_contents;

CREATE TABLE container_contents (
    tenant_id BIGINT NOT NULL,
    container_instance_id BIGINT NOT NULL REFERENCES container_instances(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, container_instance_id, item_id)
);

CREATE INDEX idx_container_contents_container
    ON container_contents(tenant_id, container_instance_id);

CREATE INDEX idx_container_contents_item
    ON container_contents(item_id);
