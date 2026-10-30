-- Canonical Account ownership already has its own column. Retain private numeric
-- source evidence unchanged; fresh ordinary characters do not fabricate that key.
ALTER TABLE characters ALTER COLUMN account_id DROP NOT NULL;

CREATE INDEX idx_characters_ordinary_account_uuid
    ON characters (tenant_id, account_uuid, playable_state_key, id)
    WHERE tenant_uuid IS NULL AND account_uuid IS NOT NULL;
