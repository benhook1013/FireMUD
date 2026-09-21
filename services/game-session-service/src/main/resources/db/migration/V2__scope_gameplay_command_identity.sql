-- Gameplay command identity is scoped to the owning tenant and game instance.
-- Keep a non-unique command_id index for correlation and diagnostics only.
DROP INDEX IF EXISTS idx_gameplay_command_command_id;

ALTER TABLE gameplay_command
    DROP CONSTRAINT IF EXISTS gameplay_command_command_id_key;

CREATE UNIQUE INDEX idx_gameplay_command_tenant_instance_command_id
    ON gameplay_command USING btree (tenant_id, game_instance_id, command_id);

CREATE INDEX idx_gameplay_command_command_id
    ON gameplay_command USING btree (command_id);
