CREATE TABLE characters (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL,
    level INT NOT NULL DEFAULT 0,
    experience INT NOT NULL DEFAULT 0,
    strength INT NOT NULL DEFAULT 0,
    agility INT NOT NULL DEFAULT 0,
    intelligence INT NOT NULL DEFAULT 0,
    stamina INT NOT NULL DEFAULT 0,
    health INT NOT NULL DEFAULT 0,
    mana INT NOT NULL DEFAULT 0,
    last_login_at TIMESTAMP NULL
);

CREATE TABLE npcs (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL
);

CREATE TABLE items (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL
);

CREATE TABLE inventory (
    character_id BIGINT NOT NULL REFERENCES characters(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INT NOT NULL,
    PRIMARY KEY (character_id, item_id)
);
