-- Gameplay command identity is scoped to the owning tenant and game instance.
-- Keep a non-unique command_id index for correlation and diagnostics only.
DROP INDEX IF EXISTS idx_gameplay_command_command_id;

ALTER TABLE gameplay_command
    DROP CONSTRAINT IF EXISTS gameplay_command_command_id_key;

CREATE UNIQUE INDEX idx_gameplay_command_tenant_instance_command_id
    ON gameplay_command USING btree (tenant_id, game_instance_id, command_id);

CREATE INDEX idx_gameplay_command_command_id
    ON gameplay_command USING btree (command_id);

-- Remote coordinator identity follows the origin command's complete runtime scope.
-- Keep a tenant/command correlation index for control-plane diagnostics, but do not
-- use it as an idempotency key because command IDs may be reused by game instance.
DROP INDEX IF EXISTS idx_remote_command_coordinator_command_id;

CREATE UNIQUE INDEX idx_remote_command_coordinator_tenant_origin_instance_cmd
    ON remote_command_coordinator USING btree (tenant_id, origin_game_instance_id, command_id);

CREATE INDEX idx_remote_command_coordinator_command_id
    ON remote_command_coordinator USING btree (tenant_id, command_id);

-- Admission-pointer selectors are tenant-local identities, not global identities.
DROP INDEX IF EXISTS uq_gameplay_admission_pointer_world_realm;

CREATE UNIQUE INDEX uq_gameplay_admission_pointer_tenant_world_realm
    ON gameplay_admission_pointer USING btree (tenant_id, world_slug, realm_slug);
