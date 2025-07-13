ALTER TABLE characters ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE npcs ADD COLUMN version INT NOT NULL DEFAULT 0;
CREATE INDEX idx_inventory_character_id ON inventory(character_id);
CREATE INDEX idx_inventory_item_id ON inventory(item_id);
CREATE INDEX idx_character_friend_character_id ON character_friend(character_id);
CREATE INDEX idx_character_friend_friend_id ON character_friend(friend_id);
