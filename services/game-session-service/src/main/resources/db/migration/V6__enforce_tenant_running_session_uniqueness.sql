CREATE UNIQUE INDEX IF NOT EXISTS uq_game_instances_running_tenant_owner
    ON game_instances (tenant_id, owner_account_id)
    WHERE status = 'RUNNING';
