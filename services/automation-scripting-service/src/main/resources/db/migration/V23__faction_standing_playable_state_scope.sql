ALTER TABLE faction_standing ADD COLUMN playable_state_key VARCHAR(120);

UPDATE faction_standing
SET playable_state_key = 'shared-live'
WHERE playable_state_key IS NULL;

ALTER TABLE faction_standing ALTER COLUMN playable_state_key SET NOT NULL;

CREATE INDEX idx_faction_standing_scope
    ON faction_standing(tenant_id, character_id, playable_state_key, faction_id);
