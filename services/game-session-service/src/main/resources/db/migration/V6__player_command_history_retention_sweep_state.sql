CREATE TABLE player_command_history_retention_sweep_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE,
    cursor_tenant_id BIGINT,
    cursor_game_instance_id BIGINT,
    cursor_character_id BIGINT,
    batches_since_wrap INTEGER NOT NULL DEFAULT 0
);

INSERT INTO player_command_history_retention_sweep_state (singleton) VALUES (TRUE);
