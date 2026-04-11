CREATE TABLE item_stacks (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT REFERENCES characters(id),
    equipment_slot VARCHAR(64),
    game_instance_id VARCHAR(255),
    room_instance_id VARCHAR(255),
    container_instance_id BIGINT REFERENCES container_instances(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    compatibility_fingerprint VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_item_stacks_tenant_character
    ON item_stacks(tenant_id, character_id);

CREATE INDEX idx_item_stacks_room
    ON item_stacks(tenant_id, game_instance_id, room_instance_id);

CREATE INDEX idx_item_stacks_container
    ON item_stacks(tenant_id, container_instance_id);

CREATE INDEX idx_item_stacks_item
    ON item_stacks(item_id);

CREATE UNIQUE INDEX ux_item_stacks_holder_item_fingerprint
    ON item_stacks(
        tenant_id,
        COALESCE(character_id, -1),
        COALESCE(equipment_slot, ''),
        COALESCE(game_instance_id, ''),
        COALESCE(room_instance_id, ''),
        COALESCE(container_instance_id, -1),
        item_id,
        compatibility_fingerprint
    );

INSERT INTO item_stacks (
    tenant_id,
    character_id,
    equipment_slot,
    game_instance_id,
    room_instance_id,
    container_instance_id,
    item_id,
    compatibility_fingerprint,
    quantity
)
SELECT
    ii.tenant_id,
    ii.character_id,
    ii.equipment_slot,
    ii.game_instance_id,
    ii.room_instance_id,
    ii.container_instance_id,
    ii.item_id,
    CONCAT('item-definition:', ii.item_id),
    COUNT(*)
FROM item_instances ii
JOIN items i ON i.id = ii.item_id
WHERE i.is_stackable = true
  AND i.is_container = false
  AND (i.equipment_slot IS NULL OR BTRIM(i.equipment_slot) = '')
GROUP BY
    ii.tenant_id,
    ii.character_id,
    ii.equipment_slot,
    ii.game_instance_id,
    ii.room_instance_id,
    ii.container_instance_id,
    ii.item_id;

DELETE FROM item_instances ii
USING items i
WHERE ii.item_id = i.id
  AND i.is_stackable = true
  AND i.is_container = false
  AND (i.equipment_slot IS NULL OR BTRIM(i.equipment_slot) = '');
