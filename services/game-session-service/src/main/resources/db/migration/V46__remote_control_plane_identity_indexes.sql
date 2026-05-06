create index if not exists idx_remote_command_coordinator_work_item_updated
    on remote_command_coordinator (
        tenant_id,
        automation_work_item_id,
        updated_at
    );

create index if not exists idx_remote_followup_identity_due
    on remote_followup (
        tenant_id,
        automation_work_item_id,
        target_entity_id,
        effect_key,
        failure_code,
        due_tick_id
    );

create index if not exists idx_remote_followup_result_identity_observed
    on remote_followup_result (
        tenant_id,
        automation_work_item_id,
        result_command_id,
        observed_at
    );
