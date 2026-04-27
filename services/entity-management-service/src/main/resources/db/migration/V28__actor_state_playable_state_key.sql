ALTER TABLE actor_resource_states ADD COLUMN playable_state_key VARCHAR(120);

UPDATE actor_resource_states state
SET playable_state_key = characters.playable_state_key
FROM characters
WHERE state.character_id = characters.id
  AND state.playable_state_key IS NULL;

ALTER TABLE actor_resource_states ALTER COLUMN playable_state_key SET NOT NULL;

ALTER TABLE actor_resource_states DROP CONSTRAINT uq_actor_resource_state;

DROP INDEX IF EXISTS idx_actor_resource_state_character;

ALTER TABLE actor_resource_states DROP COLUMN game_instance_id;

ALTER TABLE actor_resource_states
    ADD CONSTRAINT uq_actor_resource_state UNIQUE (tenant_id, playable_state_key, character_id, stat_key);

CREATE INDEX idx_actor_resource_state_character
    ON actor_resource_states (tenant_id, playable_state_key, character_id);

ALTER TABLE actor_active_conditions ADD COLUMN playable_state_key VARCHAR(120);

UPDATE actor_active_conditions condition
SET playable_state_key = characters.playable_state_key
FROM characters
WHERE condition.character_id = characters.id
  AND condition.playable_state_key IS NULL;

ALTER TABLE actor_active_conditions ALTER COLUMN playable_state_key SET NOT NULL;

DROP INDEX IF EXISTS idx_actor_active_conditions_character;
DROP INDEX IF EXISTS idx_actor_active_conditions_expiry;

ALTER TABLE actor_active_conditions DROP COLUMN game_instance_id;

CREATE INDEX idx_actor_active_conditions_character
    ON actor_active_conditions (tenant_id, playable_state_key, character_id);

CREATE INDEX idx_actor_active_conditions_expiry
    ON actor_active_conditions (tenant_id, playable_state_key, expires_at);
