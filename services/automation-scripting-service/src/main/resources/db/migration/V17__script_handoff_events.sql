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
    target_entity_id VARCHAR(64) NOT NULL,
    handoff_outcome VARCHAR(128) NOT NULL,
    handoff_reason VARCHAR(256) NOT NULL,
    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    row_version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_script_handoff_events_scope
    ON script_handoff_events(tenant_id, game_instance_id, script_patch_version, observed_at);

CREATE INDEX idx_script_handoff_events_work_item
    ON script_handoff_events(work_item_id, command_ordinal, observed_at);
