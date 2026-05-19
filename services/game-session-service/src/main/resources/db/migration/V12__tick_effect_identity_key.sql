ALTER TABLE tick_effect
    ADD COLUMN effect_key VARCHAR(160);

UPDATE tick_effect
SET effect_key = CASE
    WHEN command_id IS NOT NULL AND command_id <> '' THEN 'command:' || command_id
    ELSE 'effect:' || effect_id
END
WHERE effect_key IS NULL;

ALTER TABLE tick_effect
    ALTER COLUMN effect_key SET NOT NULL;

CREATE INDEX idx_tick_effect_effect_key ON tick_effect (effect_key);
CREATE UNIQUE INDEX idx_tick_effect_batch_effect_key ON tick_effect (tick_batch_id, effect_key);
