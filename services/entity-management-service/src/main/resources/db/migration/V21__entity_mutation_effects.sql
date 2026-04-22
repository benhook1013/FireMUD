CREATE TABLE entity_mutation_effects (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    effect_id VARCHAR(128) NOT NULL,
    operation_name VARCHAR(80) NOT NULL,
    response_type VARCHAR(255),
    response_payload BYTEA,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_entity_mutation_effects_tenant_effect
    ON entity_mutation_effects(tenant_id, effect_id);
