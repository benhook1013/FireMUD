ALTER TABLE script_handoff_events
    ADD COLUMN emitted_command_text VARCHAR(1024) NOT NULL DEFAULT '';
