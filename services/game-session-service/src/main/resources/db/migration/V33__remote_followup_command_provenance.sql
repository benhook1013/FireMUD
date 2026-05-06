ALTER TABLE remote_command_coordinator
    ADD COLUMN script_patch_version VARCHAR(128),
    ADD COLUMN plugin_id VARCHAR(128),
    ADD COLUMN plugin_version_id VARCHAR(128);

ALTER TABLE remote_followup
    ADD COLUMN script_patch_version VARCHAR(128),
    ADD COLUMN plugin_id VARCHAR(128),
    ADD COLUMN plugin_version_id VARCHAR(128);

ALTER TABLE remote_followup_result
    ADD COLUMN script_patch_version VARCHAR(128),
    ADD COLUMN plugin_id VARCHAR(128),
    ADD COLUMN plugin_version_id VARCHAR(128);
