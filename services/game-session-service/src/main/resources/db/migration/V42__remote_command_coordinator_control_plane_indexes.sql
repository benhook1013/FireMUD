CREATE INDEX idx_remote_command_coordinator_target_scope_state
    ON remote_command_coordinator (
        tenant_id,
        target_game_instance_id,
        target_region_id,
        target_region_epoch,
        state,
        updated_at
    );

CREATE INDEX idx_remote_command_coordinator_provenance_updated
    ON remote_command_coordinator (
        tenant_id,
        script_id,
        plugin_id,
        automation_dispatch_id,
        command_id,
        updated_at
    );
