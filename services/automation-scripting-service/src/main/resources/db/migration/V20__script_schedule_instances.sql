create table script_schedule_instances (
    id bigserial primary key,
    tenant_id varchar(64) not null,
    game_instance_id varchar(64) not null,
    script_patch_version varchar(128) not null,
    script_id varchar(100) not null,
    plugin_id varchar(128) not null default '',
    plugin_version_id varchar(128) not null default '',
    event_type varchar(64) not null,
    schedule_definition_id varchar(160) not null,
    schedule_kind varchar(32) not null,
    cadence_value bigint not null,
    cadence_unit varchar(32) not null,
    priority_tag varchar(32) not null default 'normal',
    materialization_status varchar(64) not null,
    next_due_at timestamp with time zone null,
    next_due_tick_id bigint null,
    observed_runtime_version_id varchar(64) not null default '',
    last_observed_control_plane_request_id varchar(128) not null default '',
    schedule_metadata_json text not null,
    schedule_semantics_hash varchar(64) not null,
    pin_observed_at timestamp with time zone not null default 'epoch',
    materialized_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    row_version integer not null default 0
);

create unique index uq_script_schedule_instance_scope
    on script_schedule_instances (
        tenant_id,
        game_instance_id,
        plugin_id,
        plugin_version_id,
        schedule_definition_id
    );

create index idx_script_schedule_instance_scope_updated
    on script_schedule_instances (tenant_id, game_instance_id, updated_at desc);

create index idx_script_schedule_instance_patch
    on script_schedule_instances (tenant_id, game_instance_id, script_patch_version, updated_at desc);
