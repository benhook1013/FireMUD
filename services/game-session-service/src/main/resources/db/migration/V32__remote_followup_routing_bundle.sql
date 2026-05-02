ALTER TABLE remote_command_coordinator
    ADD COLUMN playable_state_scope VARCHAR(32),
    ADD COLUMN world_slug VARCHAR(64),
    ADD COLUMN realm_slug VARCHAR(64),
    ADD COLUMN pointer_version BIGINT;

ALTER TABLE remote_followup
    ADD COLUMN playable_state_scope VARCHAR(32),
    ADD COLUMN world_slug VARCHAR(64),
    ADD COLUMN realm_slug VARCHAR(64),
    ADD COLUMN pointer_version BIGINT;

ALTER TABLE remote_followup_result
    ADD COLUMN playable_state_scope VARCHAR(32),
    ADD COLUMN world_slug VARCHAR(64),
    ADD COLUMN realm_slug VARCHAR(64),
    ADD COLUMN pointer_version BIGINT;
