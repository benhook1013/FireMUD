CREATE TABLE game_authored_help_topic (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    game_template_id BIGINT NOT NULL REFERENCES game_templates(id) ON DELETE CASCADE,
    canonical_topic_key VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_game_authored_help_topic_scope_key
        UNIQUE (tenant_id, game_template_id, canonical_topic_key)
);

CREATE INDEX idx_game_authored_help_topic_resolve
    ON game_authored_help_topic (tenant_id, game_template_id, canonical_topic_key)
    WHERE published;

CREATE TABLE game_authored_help_topic_alias (
    help_topic_id BIGINT NOT NULL REFERENCES game_authored_help_topic(id) ON DELETE CASCADE,
    tenant_id VARCHAR(36) NOT NULL,
    game_template_id BIGINT NOT NULL,
    alias_key VARCHAR(255) NOT NULL,
    PRIMARY KEY (help_topic_id, alias_key),
    CONSTRAINT uq_game_authored_help_topic_alias_scope_key
        UNIQUE (tenant_id, game_template_id, alias_key)
);

CREATE INDEX idx_game_authored_help_topic_alias_resolve
    ON game_authored_help_topic_alias (tenant_id, game_template_id, alias_key);
