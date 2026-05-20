ALTER TABLE version
    ADD COLUMN version_state VARCHAR(32),
    ADD COLUMN version_state_epoch BIGINT,
    ADD COLUMN updated_at TIMESTAMP;

UPDATE version
SET version_state = 'PUBLISHED',
    version_state_epoch = 1,
    updated_at = COALESCE(created_at, CURRENT_TIMESTAMP)
WHERE version_state IS NULL
   OR version_state_epoch IS NULL
   OR updated_at IS NULL;

ALTER TABLE version
    ALTER COLUMN version_state SET NOT NULL;

ALTER TABLE version
    ALTER COLUMN version_state SET DEFAULT 'DRAFT';

ALTER TABLE version
    ALTER COLUMN version_state_epoch SET NOT NULL;

ALTER TABLE version
    ALTER COLUMN version_state_epoch SET DEFAULT 1;

ALTER TABLE version
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE version
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
