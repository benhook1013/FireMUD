ALTER TABLE gameplay_command
    ADD COLUMN enqueue_seq BIGINT;

UPDATE gameplay_command
SET enqueue_seq = id
WHERE enqueue_seq IS NULL;

ALTER TABLE gameplay_command
    ALTER COLUMN enqueue_seq SET NOT NULL;

CREATE INDEX idx_gameplay_command_tenant_instance_enqueue_seq
    ON gameplay_command (tenant_id, game_instance_id, enqueue_seq);
