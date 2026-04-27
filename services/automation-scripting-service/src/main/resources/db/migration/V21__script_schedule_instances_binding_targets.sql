alter table script_schedule_instances
    add column target_scope_type varchar(32) not null default '',
    add column target_scope_id varchar(128) not null default '',
    add column binding_priority integer not null default 0,
    add column requires_exclusive_event boolean not null default false;

drop index if exists uq_script_schedule_instance_scope;

create unique index uq_script_schedule_instance_scope
    on script_schedule_instances (
        tenant_id,
        game_instance_id,
        plugin_id,
        plugin_version_id,
        target_scope_type,
        target_scope_id,
        schedule_definition_id
    );
