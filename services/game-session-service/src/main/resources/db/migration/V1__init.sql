CREATE TABLE IF NOT EXISTS game_instances (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    runtime_version VARCHAR(100) NOT NULL,
    script_patch_version VARCHAR(100),
    owner_account_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
