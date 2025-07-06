CREATE TABLE character (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL
);

CREATE TABLE npc (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL
);

CREATE TABLE item (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    tenant_id BIGINT NOT NULL
);

CREATE TABLE inventory (
    character_id BIGINT NOT NULL REFERENCES character(id),
    item_id BIGINT NOT NULL REFERENCES item(id),
    quantity INT NOT NULL,
    PRIMARY KEY (character_id, item_id)
);
