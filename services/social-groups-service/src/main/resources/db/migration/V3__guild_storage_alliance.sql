CREATE TABLE guild_storage_items (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    item_name VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL
);

CREATE TABLE guild_alliances (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL,
    ally_guild_id BIGINT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
