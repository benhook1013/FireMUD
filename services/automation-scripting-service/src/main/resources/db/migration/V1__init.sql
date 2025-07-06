CREATE TABLE script (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    version INT NOT NULL,
    content TEXT NOT NULL
);

CREATE TABLE npc_memory (
    id BIGSERIAL PRIMARY KEY,
    npc_id BIGINT NOT NULL,
    key VARCHAR(100) NOT NULL,
    value VARCHAR(255),
    tenant_id BIGINT NOT NULL
);
