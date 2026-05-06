ALTER TABLE script_handoff_events
    ADD COLUMN IF NOT EXISTS target_game_instance_id VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS target_region_id VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS target_region_epoch BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS remote_coordinator_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS remote_followup_id VARCHAR(128);
