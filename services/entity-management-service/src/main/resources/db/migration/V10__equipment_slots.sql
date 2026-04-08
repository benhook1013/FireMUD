ALTER TABLE items ADD COLUMN equipment_slot VARCHAR(32);

CREATE TABLE character_equipment (
    character_id BIGINT NOT NULL REFERENCES characters(id),
    slot VARCHAR(32) NOT NULL,
    item_id BIGINT NOT NULL REFERENCES items(id),
    version INT NOT NULL DEFAULT 0,
    PRIMARY KEY (character_id, slot)
);

CREATE INDEX idx_character_equipment_character_id
    ON character_equipment(character_id);
