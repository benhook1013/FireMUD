ALTER TABLE chat_messages ADD COLUMN effect_id VARCHAR(64);

CREATE UNIQUE INDEX ux_chat_messages_tenant_effect_id
    ON chat_messages (tenant_id, effect_id)
    WHERE effect_id IS NOT NULL;
