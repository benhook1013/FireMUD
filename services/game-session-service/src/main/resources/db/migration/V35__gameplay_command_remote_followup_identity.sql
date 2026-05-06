ALTER TABLE gameplay_command
    ADD COLUMN remote_coordinator_id VARCHAR(128),
    ADD COLUMN remote_followup_id VARCHAR(128);

CREATE UNIQUE INDEX idx_gameplay_command_remote_followup
    ON gameplay_command (tenant_id, game_instance_id, region_id, region_epoch, remote_followup_id)
    WHERE remote_followup_id IS NOT NULL;
