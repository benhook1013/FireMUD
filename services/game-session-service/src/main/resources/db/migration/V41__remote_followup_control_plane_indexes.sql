CREATE INDEX idx_remote_followup_origin_scope_due
    ON remote_followup (
        tenant_id,
        origin_game_instance_id,
        origin_region_id,
        due_tick_id
    );

CREATE INDEX idx_remote_followup_target_scope_epoch_due
    ON remote_followup (
        tenant_id,
        target_game_instance_id,
        target_region_id,
        target_region_epoch,
        due_tick_id
    );

CREATE INDEX idx_remote_followup_provenance_due
    ON remote_followup (
        tenant_id,
        script_id,
        plugin_id,
        automation_dispatch_id,
        command_id,
        due_tick_id
    );

CREATE INDEX idx_remote_followup_result_scope_observed
    ON remote_followup_result (
        tenant_id,
        origin_region_id,
        target_region_id,
        observed_at
    );

CREATE INDEX idx_remote_followup_result_provenance_observed
    ON remote_followup_result (
        tenant_id,
        script_id,
        plugin_id,
        automation_dispatch_id,
        command_id,
        observed_at
    );
