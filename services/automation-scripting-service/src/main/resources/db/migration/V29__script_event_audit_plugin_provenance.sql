ALTER TABLE script_event_audit
    ADD COLUMN plugin_id VARCHAR(128),
    ADD COLUMN plugin_version_id VARCHAR(128);
