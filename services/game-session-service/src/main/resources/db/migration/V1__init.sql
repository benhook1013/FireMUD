CREATE TABLE game_instances (
    id BIGSERIAL PRIMARY KEY,
    version_id BIGINT NOT NULL,
    owner_account_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    tenant_id BIGINT NOT NULL
);
