CREATE TABLE character_friend (
    character_id BIGINT NOT NULL REFERENCES characters(id),
    friend_id BIGINT NOT NULL REFERENCES characters(id),
    tenant_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (character_id, friend_id)
);
