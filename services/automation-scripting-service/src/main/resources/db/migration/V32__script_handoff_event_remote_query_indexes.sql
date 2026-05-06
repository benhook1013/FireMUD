CREATE INDEX idx_script_handoff_events_target_scope
    ON script_handoff_events(
        tenant_id,
        target_game_instance_id,
        target_region_id,
        target_region_epoch,
        observed_at DESC
    );

CREATE INDEX idx_script_handoff_events_remote_ids
    ON script_handoff_events(
        tenant_id,
        remote_coordinator_id,
        remote_followup_id,
        observed_at DESC
    );

CREATE INDEX idx_script_handoff_events_origin_identity
    ON script_handoff_events(
        tenant_id,
        script_id,
        plugin_id,
        automation_dispatch_id,
        observed_at DESC
    );
