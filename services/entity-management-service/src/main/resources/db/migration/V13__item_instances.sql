CREATE TABLE item_instances (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT REFERENCES characters(id),
    equipment_slot VARCHAR(64),
    game_instance_id VARCHAR(255),
    room_instance_id VARCHAR(255),
    item_id BIGINT NOT NULL REFERENCES items(id),
    version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_item_instances_tenant_character
    ON item_instances(tenant_id, character_id);

CREATE INDEX idx_item_instances_tenant_equipment
    ON item_instances(tenant_id, character_id, equipment_slot);

CREATE INDEX idx_item_instances_room
    ON item_instances(tenant_id, game_instance_id, room_instance_id);

CREATE INDEX idx_item_instances_item
    ON item_instances(item_id);

ALTER TABLE container_instances
    ADD COLUMN item_instance_id BIGINT REFERENCES item_instances(id);

CREATE UNIQUE INDEX ux_container_instances_item_instance
    ON container_instances(item_instance_id)
    WHERE item_instance_id IS NOT NULL;

DROP INDEX IF EXISTS ux_container_instances_inventory;
DROP INDEX IF EXISTS ux_container_instances_equipment;
DROP INDEX IF EXISTS ux_container_instances_room;
