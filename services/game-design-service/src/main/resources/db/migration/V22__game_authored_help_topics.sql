ALTER TABLE game_templates
    ADD CONSTRAINT uq_game_templates_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE game_authored_help_topic (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    game_template_id BIGINT NOT NULL,
    canonical_topic_key VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_game_authored_help_topic_scope_key
        UNIQUE (tenant_id, game_template_id, canonical_topic_key),
    CONSTRAINT uq_game_authored_help_topic_id_scope
        UNIQUE (id, tenant_id, game_template_id),
    CONSTRAINT fk_game_authored_help_topic_template_scope
        FOREIGN KEY (tenant_id, game_template_id)
        REFERENCES game_templates (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_game_authored_help_topic_resolve
    ON game_authored_help_topic (tenant_id, game_template_id, canonical_topic_key)
    WHERE published;

CREATE TABLE game_authored_help_topic_key (
    help_topic_id BIGINT NOT NULL REFERENCES game_authored_help_topic(id) ON DELETE CASCADE,
    tenant_id VARCHAR(36) NOT NULL,
    game_template_id BIGINT NOT NULL,
    lookup_key VARCHAR(255) NOT NULL,
    PRIMARY KEY (help_topic_id, lookup_key),
    CONSTRAINT uq_game_authored_help_topic_key_scope
        UNIQUE (tenant_id, game_template_id, lookup_key),
    CONSTRAINT fk_game_authored_help_topic_key_scope
        FOREIGN KEY (help_topic_id, tenant_id, game_template_id)
        REFERENCES game_authored_help_topic (id, tenant_id, game_template_id) ON DELETE CASCADE
);

CREATE INDEX idx_game_authored_help_topic_key_resolve
    ON game_authored_help_topic_key (tenant_id, game_template_id, lookup_key);
