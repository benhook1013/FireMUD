ALTER TABLE remote_followup_result
    ADD COLUMN origin_game_instance_id BIGINT,
    ADD COLUMN target_game_instance_id BIGINT;

UPDATE remote_followup_result result
SET origin_game_instance_id = coordinator.origin_game_instance_id,
    target_game_instance_id = coordinator.target_game_instance_id
FROM remote_command_coordinator coordinator
WHERE coordinator.tenant_id = result.tenant_id
  AND coordinator.coordinator_id = result.coordinator_id;

ALTER TABLE remote_followup_result
    ALTER COLUMN origin_game_instance_id SET NOT NULL,
    ALTER COLUMN target_game_instance_id SET NOT NULL;

CREATE INDEX idx_remote_followup_result_scope_observed
    ON remote_followup_result (
        tenant_id,
        origin_game_instance_id,
        origin_region_id,
        target_game_instance_id,
        target_region_id,
        observed_at
    );
