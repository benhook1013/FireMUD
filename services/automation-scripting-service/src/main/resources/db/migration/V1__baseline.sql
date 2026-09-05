CREATE TABLE scripts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    version VARCHAR(20) NOT NULL,
    definition TEXT NOT NULL,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_scripts_tenant_id ON scripts(tenant_id);

CREATE TABLE npc_memory (
    id BIGSERIAL PRIMARY KEY,
    npc_id BIGINT NOT NULL,
    key VARCHAR(100) NOT NULL,
    value VARCHAR(255),
    tenant_id BIGINT NOT NULL,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_npc_memory_tenant_id ON npc_memory(tenant_id);
CREATE INDEX idx_npc_memory_npc_id ON npc_memory(npc_id);

CREATE TABLE factions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_factions_tenant_id ON factions(tenant_id);

CREATE TABLE faction_standing (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    faction_id BIGINT NOT NULL REFERENCES factions(id),
    reputation INT NOT NULL DEFAULT 0,
    row_version INT NOT NULL DEFAULT 0,
    playable_state_key VARCHAR(120) NOT NULL
);

CREATE INDEX idx_faction_standing_tenant_id ON faction_standing(tenant_id);
CREATE INDEX idx_faction_standing_faction_id ON faction_standing(faction_id);
CREATE INDEX idx_faction_standing_scope
    ON faction_standing(tenant_id, character_id, playable_state_key, faction_id);

CREATE TABLE npc_formations (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    leader_npc_id BIGINT NOT NULL,
    formation_type VARCHAR(20) NOT NULL,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_npc_formations_tenant_id ON npc_formations(tenant_id);
CREATE INDEX idx_npc_formations_leader_npc_id ON npc_formations(leader_npc_id);

CREATE TABLE npc_formation_member (
    id BIGSERIAL PRIMARY KEY,
    formation_id BIGINT NOT NULL REFERENCES npc_formations(id),
    npc_id BIGINT NOT NULL,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_npc_formation_member_formation_id ON npc_formation_member(formation_id);
CREATE INDEX idx_npc_formation_member_npc_id ON npc_formation_member(npc_id);

CREATE TABLE script_event_ingress_audit (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64),
    region_id VARCHAR(64),
    region_epoch BIGINT,
    entity_id VARCHAR(64),
    playable_state_scope VARCHAR(32) NOT NULL DEFAULT '',
    world_slug VARCHAR(64) NOT NULL DEFAULT '',
    realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    pointer_version VARCHAR(64) NOT NULL DEFAULT '',
    script_id VARCHAR(128),
    plugin_id VARCHAR(128),
    plugin_version_id VARCHAR(128),
    event_type VARCHAR(128) NOT NULL,
    event_schema_version VARCHAR(32) NOT NULL,
    quota_class VARCHAR(64) NOT NULL DEFAULT 'STANDARD_RUNTIME',
    script_patch_version VARCHAR(128) NOT NULL,
    script_event_id VARCHAR(128) NOT NULL,
    source_service VARCHAR(128) NOT NULL,
    trigger_mode VARCHAR(64) NOT NULL,
    source_kind VARCHAR(64) NOT NULL DEFAULT '',
    source_state VARCHAR(64) NOT NULL DEFAULT '',
    source_ordinal BIGINT,
    source_due_tick_id BIGINT,
    source_due_at_ms BIGINT,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    read_snapshot_token VARCHAR(512),
    payload_json TEXT,
    admitted BOOLEAN NOT NULL,
    admission_outcome VARCHAR(128) NOT NULL,
    admission_reason VARCHAR(256) NOT NULL,
    resolved_handler_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_event_ingress_audit_identity UNIQUE (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run,
        source_service
    )
);

CREATE INDEX idx_script_event_ingress_audit_tenant_created
    ON script_event_ingress_audit(tenant_id, created_at);
CREATE INDEX idx_script_event_ingress_audit_event_type
    ON script_event_ingress_audit(event_type, event_schema_version);

CREATE TABLE script_event_bindings (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_schema_version VARCHAR(32) NOT NULL,
    script_id VARCHAR(128) NOT NULL,
    binding_id VARCHAR(128) NOT NULL,
    target_scope_type VARCHAR(32) NOT NULL,
    target_scope_id VARCHAR(128) NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    priority_tag VARCHAR(32) NOT NULL DEFAULT 'normal',
    requires_exclusive_event BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_event_binding UNIQUE (
        tenant_id,
        script_patch_version,
        event_type,
        event_schema_version,
        script_id,
        binding_id,
        target_scope_type,
        target_scope_id
    )
);

CREATE INDEX idx_script_event_bindings_resolution ON script_event_bindings(
    tenant_id,
    script_patch_version,
    event_type,
    event_schema_version,
    enabled,
    priority,
    script_id,
    binding_id,
    id
);

CREATE TABLE script_work_items (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    region_id VARCHAR(64) NOT NULL,
    region_epoch BIGINT NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    playable_state_scope VARCHAR(32) NOT NULL DEFAULT '',
    world_slug VARCHAR(64) NOT NULL DEFAULT '',
    realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    pointer_version VARCHAR(64) NOT NULL DEFAULT '',
    script_id VARCHAR(128) NOT NULL,
    plugin_id VARCHAR(128),
    plugin_version_id VARCHAR(128),
    event_type VARCHAR(128) NOT NULL,
    event_schema_version VARCHAR(32) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    script_event_id VARCHAR(128) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    source_service VARCHAR(128) NOT NULL,
    trigger_mode VARCHAR(64) NOT NULL,
    source_kind VARCHAR(64) NOT NULL DEFAULT '',
    source_state VARCHAR(64) NOT NULL DEFAULT '',
    source_ordinal BIGINT,
    source_due_tick_id BIGINT,
    source_due_at_ms BIGINT,
    priority_tag VARCHAR(32) NOT NULL DEFAULT 'normal',
    read_snapshot_token VARCHAR(512),
    payload_json TEXT,
    admission_epoch BIGINT NOT NULL DEFAULT 1,
    status VARCHAR(64) NOT NULL DEFAULT 'PENDING_EVALUATION',
    cancel_reason VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_work_item_trigger_identity UNIQUE (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        world_slug,
        realm_slug,
        pointer_version,
        script_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    )
);

CREATE INDEX idx_script_work_items_status_created
    ON script_work_items(status, created_at);
CREATE INDEX idx_script_work_items_entity_status
    ON script_work_items(tenant_id, game_instance_id, entity_id, status);
CREATE INDEX idx_script_work_items_scope_epoch_status
    ON script_work_items(tenant_id, game_instance_id, region_id, admission_epoch, status);
CREATE INDEX idx_script_work_items_plugin_version
    ON script_work_items(tenant_id, game_instance_id, plugin_id, plugin_version_id);

CREATE TABLE script_event_audit (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    region_id VARCHAR(64) NOT NULL,
    region_epoch BIGINT NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    playable_state_scope VARCHAR(32) NOT NULL DEFAULT '',
    world_slug VARCHAR(64) NOT NULL DEFAULT '',
    realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    pointer_version VARCHAR(64) NOT NULL DEFAULT '',
    script_id VARCHAR(128) NOT NULL,
    plugin_id VARCHAR(128),
    plugin_version_id VARCHAR(128),
    event_type VARCHAR(128) NOT NULL,
    event_schema_version VARCHAR(32) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    script_event_id VARCHAR(128) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    source_service VARCHAR(128) NOT NULL,
    trigger_mode VARCHAR(64) NOT NULL,
    source_kind VARCHAR(64) NOT NULL DEFAULT '',
    source_state VARCHAR(64) NOT NULL DEFAULT '',
    source_ordinal BIGINT,
    source_due_tick_id BIGINT,
    source_due_at_ms BIGINT,
    work_item_id BIGINT REFERENCES script_work_items(id),
    final_stage VARCHAR(64) NOT NULL,
    final_outcome VARCHAR(128) NOT NULL,
    final_reason VARCHAR(256) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_event_audit_handler_identity UNIQUE (
        tenant_id,
        game_instance_id,
        region_id,
        region_epoch,
        entity_id,
        playable_state_scope,
        world_slug,
        realm_slug,
        pointer_version,
        script_id,
        event_type,
        event_schema_version,
        script_patch_version,
        script_event_id,
        dry_run
    )
);

CREATE INDEX idx_script_event_audit_script_created
    ON script_event_audit(tenant_id, script_id, created_at);
CREATE INDEX idx_script_event_audit_outcome
    ON script_event_audit(final_stage, final_outcome);

CREATE TABLE plugin_runtime_states (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    runtime_region_id VARCHAR(64),
    runtime_region_epoch BIGINT,
    plugin_id VARCHAR(128) NOT NULL,
    active_plugin_version_id VARCHAR(128),
    pending_plugin_version_id VARCHAR(128),
    plugin_state VARCHAR(64) NOT NULL,
    status_reason VARCHAR(256) NOT NULL,
    control_plane_request_id VARCHAR(128),
    actor_principal VARCHAR(256),
    last_changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_policy_checked_at TIMESTAMP NOT NULL DEFAULT TIMESTAMP '1970-01-01 00:00:00',
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_plugin_runtime_state_scope UNIQUE (tenant_id, game_instance_id, plugin_id)
);

CREATE INDEX idx_plugin_runtime_state_tenant_instance
    ON plugin_runtime_states(tenant_id, game_instance_id);

CREATE TABLE script_patch_pin_projections (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    observed_pinned_script_patch_version VARCHAR(128) NOT NULL DEFAULT '',
    playable_state_scope VARCHAR(32) NOT NULL DEFAULT '',
    world_slug VARCHAR(64) NOT NULL DEFAULT '',
    realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    pointer_version VARCHAR(64) NOT NULL DEFAULT '',
    runtime_region_id VARCHAR(64) NOT NULL DEFAULT '',
    runtime_region_epoch BIGINT NOT NULL DEFAULT 0,
    last_observed_control_plane_request_id VARCHAR(128) NOT NULL DEFAULT '',
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    projection_refreshed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_patch_pin_projection_scope UNIQUE (tenant_id, game_instance_id)
);

CREATE INDEX idx_script_patch_pin_projection_scope
    ON script_patch_pin_projections(tenant_id, game_instance_id);

CREATE TABLE script_patch_instance_rollout_projections (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    rollout_status VARCHAR(64) NOT NULL,
    status_reason VARCHAR(256) NOT NULL,
    last_changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    projection_refreshed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_patch_instance_rollout_projection_scope
        UNIQUE (tenant_id, game_instance_id, script_patch_version)
);

CREATE INDEX idx_script_patch_instance_rollout_projection_scope
    ON script_patch_instance_rollout_projections(tenant_id, game_instance_id, script_patch_version);

CREATE TABLE automation_admission_states (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    region_id VARCHAR(64) NOT NULL,
    mode VARCHAR(64) NOT NULL DEFAULT 'NORMAL',
    admission_epoch BIGINT NOT NULL DEFAULT 1,
    control_plane_request_id VARCHAR(128),
    actor_principal VARCHAR(128),
    reason VARCHAR(256),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_automation_admission_scope UNIQUE (tenant_id, game_instance_id, region_id)
);

CREATE INDEX idx_automation_admission_scope
    ON automation_admission_states(tenant_id, game_instance_id, region_id);

CREATE TABLE script_patch_instance_rollout_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    rollout_status VARCHAR(64) NOT NULL,
    status_reason VARCHAR(256) NOT NULL,
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    projection_refreshed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_script_patch_instance_rollout_events_scope
    ON script_patch_instance_rollout_events(tenant_id, game_instance_id, script_patch_version, observed_at);

CREATE TABLE plugin_runtime_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    runtime_region_id VARCHAR(64),
    runtime_region_epoch BIGINT,
    plugin_id VARCHAR(128) NOT NULL,
    previous_plugin_version_id VARCHAR(128),
    active_plugin_version_id VARCHAR(128),
    plugin_state VARCHAR(64) NOT NULL,
    status_reason VARCHAR(256) NOT NULL,
    control_plane_request_id VARCHAR(128),
    actor_principal VARCHAR(256),
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_plugin_runtime_events_scope
    ON plugin_runtime_events(tenant_id, game_instance_id, plugin_id, observed_at);

CREATE TABLE script_handoff_events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(80) NOT NULL UNIQUE,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    script_id VARCHAR(128) NOT NULL,
    plugin_id VARCHAR(128),
    plugin_version_id VARCHAR(128),
    work_item_id BIGINT NOT NULL REFERENCES script_work_items(id),
    command_ordinal INT NOT NULL,
    automation_dispatch_id VARCHAR(128) NOT NULL,
    game_session_command_id VARCHAR(128),
    target_game_instance_id VARCHAR(64) NOT NULL DEFAULT '',
    target_region_id VARCHAR(64) NOT NULL DEFAULT '',
    target_region_epoch BIGINT NOT NULL DEFAULT 0,
    remote_coordinator_id VARCHAR(128),
    remote_followup_id VARCHAR(128),
    target_entity_id VARCHAR(64) NOT NULL,
    playable_state_scope VARCHAR(32) NOT NULL DEFAULT '',
    world_slug VARCHAR(64) NOT NULL DEFAULT '',
    realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    pointer_version VARCHAR(64) NOT NULL DEFAULT '',
    source_kind VARCHAR(64) NOT NULL DEFAULT '',
    source_state VARCHAR(64) NOT NULL DEFAULT '',
    source_ordinal BIGINT,
    source_due_tick_id BIGINT,
    source_due_at_ms BIGINT,
    emitted_command_text VARCHAR(1024) NOT NULL DEFAULT '',
    handoff_outcome VARCHAR(128) NOT NULL,
    handoff_reason VARCHAR(256) NOT NULL,
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_script_handoff_events_scope
    ON script_handoff_events(tenant_id, game_instance_id, script_patch_version, observed_at);
CREATE INDEX idx_script_handoff_events_work_item
    ON script_handoff_events(work_item_id, command_ordinal, observed_at);
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

CREATE TABLE script_schedule_definitions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    script_id VARCHAR(100) NOT NULL,
    plugin_id VARCHAR(128) NOT NULL DEFAULT '',
    plugin_version_id VARCHAR(128) NOT NULL DEFAULT '',
    event_type VARCHAR(64) NOT NULL,
    schedule_definition_id VARCHAR(160) NOT NULL,
    schedule_kind VARCHAR(32) NOT NULL,
    cadence_value BIGINT NOT NULL,
    cadence_unit VARCHAR(32) NOT NULL,
    priority_tag VARCHAR(32) NOT NULL DEFAULT 'normal',
    schedule_metadata_json TEXT NOT NULL,
    schedule_semantics_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_version INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_schedule_definition_scope UNIQUE (
        tenant_id,
        script_patch_version,
        plugin_id,
        plugin_version_id,
        schedule_definition_id
    )
);

CREATE INDEX idx_script_schedule_definition_patch
    ON script_schedule_definitions (tenant_id, script_patch_version, script_id);

CREATE TABLE script_schedule_instances (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    game_instance_id VARCHAR(64) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    script_id VARCHAR(100) NOT NULL,
    playable_state_scope VARCHAR(32) NOT NULL DEFAULT '',
    world_slug VARCHAR(64) NOT NULL DEFAULT '',
    realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    pointer_version VARCHAR(64) NOT NULL DEFAULT '',
    plugin_id VARCHAR(128) NOT NULL DEFAULT '',
    plugin_version_id VARCHAR(128) NOT NULL DEFAULT '',
    event_type VARCHAR(64) NOT NULL,
    schedule_definition_id VARCHAR(160) NOT NULL,
    schedule_kind VARCHAR(32) NOT NULL,
    cadence_value BIGINT NOT NULL,
    cadence_unit VARCHAR(32) NOT NULL,
    priority_tag VARCHAR(32) NOT NULL DEFAULT 'normal',
    target_scope_type VARCHAR(32) NOT NULL DEFAULT '',
    target_scope_id VARCHAR(128) NOT NULL DEFAULT '',
    binding_priority INTEGER NOT NULL DEFAULT 0,
    requires_exclusive_event BOOLEAN NOT NULL DEFAULT FALSE,
    materialization_status VARCHAR(64) NOT NULL,
    next_due_at TIMESTAMP WITH TIME ZONE NULL,
    next_due_tick_id BIGINT NULL,
    runtime_region_id VARCHAR(64) NOT NULL DEFAULT '',
    runtime_region_epoch BIGINT,
    last_observed_tick_id BIGINT,
    last_runtime_progress_observed_at TIMESTAMP WITH TIME ZONE,
    observed_runtime_version_id VARCHAR(64) NOT NULL DEFAULT '',
    last_observed_control_plane_request_id VARCHAR(128) NOT NULL DEFAULT '',
    schedule_metadata_json TEXT NOT NULL,
    schedule_semantics_hash VARCHAR(64) NOT NULL,
    pin_observed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT 'epoch',
    materialized_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    row_version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_schedule_instance_scope UNIQUE (
        tenant_id,
        game_instance_id,
        playable_state_scope,
        plugin_id,
        plugin_version_id,
        target_scope_type,
        target_scope_id,
        schedule_definition_id
    )
);

CREATE INDEX idx_script_schedule_instance_scope_updated
    ON script_schedule_instances (tenant_id, game_instance_id, updated_at DESC);
CREATE INDEX idx_script_schedule_instance_patch
    ON script_schedule_instances (tenant_id, game_instance_id, script_patch_version, updated_at DESC);
CREATE INDEX idx_script_schedule_instances_runtime_progress
    ON script_schedule_instances (
        tenant_id,
        game_instance_id,
        runtime_region_id,
        runtime_region_epoch,
        next_due_tick_id
    );

CREATE TABLE script_patch_readiness_projections (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    script_patch_version VARCHAR(128) NOT NULL,
    readiness_status VARCHAR(64) NOT NULL,
    status_reason VARCHAR(256) NOT NULL,
    superseded_by_script_patch_version VARCHAR(128) NOT NULL DEFAULT '',
    last_changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uq_script_patch_readiness_projection_scope
        UNIQUE (tenant_id, script_patch_version)
);

CREATE INDEX idx_script_patch_readiness_projection_tenant_status
    ON script_patch_readiness_projections (tenant_id, readiness_status, last_changed_at DESC);
