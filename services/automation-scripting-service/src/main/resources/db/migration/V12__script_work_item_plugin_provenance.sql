ALTER TABLE script_work_items
    ADD COLUMN plugin_id VARCHAR(128),
    ADD COLUMN plugin_version_id VARCHAR(128);

CREATE INDEX idx_script_work_items_plugin_version
    ON script_work_items (tenant_id, game_instance_id, plugin_id, plugin_version_id);
