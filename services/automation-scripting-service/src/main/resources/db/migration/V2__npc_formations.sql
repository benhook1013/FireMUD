CREATE TABLE npc_formations (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    leader_npc_id BIGINT NOT NULL,
    formation_type VARCHAR(20) NOT NULL
);

CREATE TABLE npc_formation_member (
    id BIGSERIAL PRIMARY KEY,
    formation_id BIGINT NOT NULL REFERENCES npc_formations(id),
    npc_id BIGINT NOT NULL
);
