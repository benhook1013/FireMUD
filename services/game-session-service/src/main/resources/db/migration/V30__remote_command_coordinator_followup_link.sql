ALTER TABLE remote_command_coordinator
    ADD COLUMN followup_id VARCHAR(64);

UPDATE remote_command_coordinator coordinator
SET followup_id = followup.followup_id
FROM remote_followup followup
WHERE coordinator.followup_id IS NULL
  AND coordinator.tenant_id = followup.tenant_id
  AND coordinator.origin_region_id = followup.origin_region_id
  AND coordinator.origin_region_epoch = followup.origin_region_epoch
  AND coordinator.target_region_id = followup.target_region_id
  AND coordinator.target_region_epoch = followup.target_region_epoch;

ALTER TABLE remote_command_coordinator
    ALTER COLUMN followup_id SET NOT NULL;

CREATE INDEX idx_remote_command_coordinator_followup_id
    ON remote_command_coordinator (tenant_id, followup_id);
