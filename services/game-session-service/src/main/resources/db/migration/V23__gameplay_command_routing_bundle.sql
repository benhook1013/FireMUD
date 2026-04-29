ALTER TABLE gameplay_command
    ADD COLUMN playable_state_scope VARCHAR(32) NOT NULL DEFAULT '',
    ADD COLUMN world_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN realm_slug VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN pointer_version BIGINT;
