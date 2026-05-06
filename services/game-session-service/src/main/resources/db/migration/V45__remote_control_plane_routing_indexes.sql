create index if not exists idx_remote_command_coordinator_routing_updated
    on remote_command_coordinator (
        tenant_id,
        script_patch_version,
        plugin_version_id,
        playable_state_scope,
        world_slug,
        realm_slug,
        pointer_version,
        updated_at
    );

create index if not exists idx_remote_followup_routing_due
    on remote_followup (
        tenant_id,
        script_patch_version,
        plugin_version_id,
        playable_state_scope,
        world_slug,
        realm_slug,
        pointer_version,
        payload_kind,
        origin_source_kind,
        due_tick_id
    );

create index if not exists idx_remote_followup_result_routing_observed
    on remote_followup_result (
        tenant_id,
        script_patch_version,
        plugin_version_id,
        playable_state_scope,
        world_slug,
        realm_slug,
        pointer_version,
        result_error_code,
        observed_at
    );
