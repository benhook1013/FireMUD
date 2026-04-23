alter table script_schedule_instances
    add column runtime_region_id varchar(64) not null default '',
    add column runtime_region_epoch bigint,
    add column last_observed_tick_id bigint,
    add column last_runtime_progress_observed_at timestamp with time zone;

create index if not exists idx_script_schedule_instances_runtime_progress
    on script_schedule_instances (
        tenant_id,
        game_instance_id,
        runtime_region_id,
        runtime_region_epoch,
        next_due_tick_id
    );
