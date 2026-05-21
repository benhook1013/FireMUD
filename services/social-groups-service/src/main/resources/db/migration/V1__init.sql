CREATE TABLE guilds (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    owner_account_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE guild_members (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    guild_id BIGINT NOT NULL REFERENCES guilds(id),
    account_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL
);

CREATE TABLE chat_messages (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    sender_account_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    guild_id BIGINT,
    recipient_account_id BIGINT
);

CREATE TABLE friend_links (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    friend_account_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
