DROP INDEX IF EXISTS idx_remote_followup_target_region_epoch_effect;

CREATE UNIQUE INDEX idx_remote_followup_target_instance_region_epoch_effect
    ON remote_followup USING btree (tenant_id, target_game_instance_id, target_region_id, target_region_epoch, effect_key);
