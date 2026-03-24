CREATE TABLE scripts (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    version VARCHAR(20) NOT NULL,
    definition TEXT NOT NULL
);

CREATE TABLE npc_memory (
    id BIGSERIAL PRIMARY KEY,
    npc_id BIGINT NOT NULL,
    key VARCHAR(100) NOT NULL,
    value VARCHAR(255),
    tenant_id BIGINT NOT NULL
);

CREATE TABLE factions (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT
);

CREATE TABLE faction_standing (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    faction_id BIGINT NOT NULL REFERENCES factions(id),
    reputation INT NOT NULL DEFAULT 0
);
