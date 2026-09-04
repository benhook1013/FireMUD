ALTER TABLE gameplay_command
    ADD COLUMN script_pin_epoch bigint;

ALTER TABLE remote_command_coordinator
    ADD COLUMN script_pin_epoch bigint;

ALTER TABLE remote_followup
    ADD COLUMN script_pin_epoch bigint;
