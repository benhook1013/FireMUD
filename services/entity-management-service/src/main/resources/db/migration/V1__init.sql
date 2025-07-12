CREATE TABLE characters (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL
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
