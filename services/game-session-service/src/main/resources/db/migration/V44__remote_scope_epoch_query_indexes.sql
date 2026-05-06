CREATE INDEX idx_remote_followup_origin_scope_epoch_due
    ON remote_followup (
        tenant_id,
        origin_game_instance_id,
        origin_region_id,
        origin_region_epoch,
        due_tick_id
    );

DROP INDEX IF EXISTS idx_remote_followup_result_scope_observed;

CREATE INDEX idx_remote_followup_result_scope_observed
    ON remote_followup_result (
        tenant_id,
        origin_game_instance_id,
        origin_region_id,
        origin_region_epoch,
        target_game_instance_id,
        target_region_id,
        target_region_epoch,
        observed_at
    );
