CREATE TABLE guild (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    owner_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL
);

CREATE TABLE guild_member (
    guild_id BIGINT NOT NULL REFERENCES guild(id),
    account_id BIGINT NOT NULL,
    role VARCHAR(50) NOT NULL,
    PRIMARY KEY (guild_id, account_id)
);

CREATE TABLE chat_message (
    id BIGSERIAL PRIMARY KEY,
    sender_id BIGINT NOT NULL,
    channel VARCHAR(50),
    message TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE friend_link (
    account_id BIGINT NOT NULL,
    friend_account_id BIGINT NOT NULL,
    PRIMARY KEY (account_id, friend_account_id)
);
