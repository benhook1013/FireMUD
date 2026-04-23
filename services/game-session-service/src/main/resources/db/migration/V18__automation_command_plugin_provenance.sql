ALTER TABLE gameplay_command
    ADD COLUMN plugin_id VARCHAR(128),
    ADD COLUMN plugin_version_id VARCHAR(128);

CREATE INDEX idx_gameplay_command_automation_plugin_version
    ON gameplay_command (tenant_id, game_instance_id, source_type, region_id, plugin_id, plugin_version_id);
