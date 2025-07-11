CREATE TABLE generation_rule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    value VARCHAR(255)
);
