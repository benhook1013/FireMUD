CREATE TABLE room_ground_inventory (
    tenant_id BIGINT NOT NULL,
    game_instance_id VARCHAR(100) NOT NULL,
    room_instance_id VARCHAR(100) NOT NULL,
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, game_instance_id, room_instance_id, item_id)
);

CREATE INDEX idx_room_ground_inventory_room
    ON room_ground_inventory(tenant_id, game_instance_id, room_instance_id);

CREATE INDEX idx_room_ground_inventory_item ON room_ground_inventory(item_id);
