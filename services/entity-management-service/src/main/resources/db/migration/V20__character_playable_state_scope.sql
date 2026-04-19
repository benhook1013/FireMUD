ALTER TABLE characters
    ADD COLUMN IF NOT EXISTS playable_state_key VARCHAR(120) NOT NULL DEFAULT 'shared-live';

UPDATE characters
SET playable_state_key = 'shared-live'
WHERE playable_state_key IS NULL OR playable_state_key = '';

CREATE INDEX IF NOT EXISTS idx_characters_tenant_account_playable_state
    ON characters (tenant_id, account_id, playable_state_key);

CREATE INDEX IF NOT EXISTS idx_characters_tenant_playable_state_lower_name
    ON characters (tenant_id, playable_state_key, lower(name));
