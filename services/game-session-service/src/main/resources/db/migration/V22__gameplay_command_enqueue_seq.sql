ALTER TABLE gameplay_command
    ADD COLUMN enqueue_seq BIGINT;

CREATE SEQUENCE gameplay_command_enqueue_seq_seq
    OWNED BY gameplay_command.enqueue_seq;

UPDATE gameplay_command
SET enqueue_seq = id
WHERE enqueue_seq IS NULL;

SELECT setval(
    'gameplay_command_enqueue_seq_seq',
    COALESCE((SELECT MAX(enqueue_seq) FROM gameplay_command), 0) + 1,
    false
);

ALTER TABLE gameplay_command
    ALTER COLUMN enqueue_seq SET DEFAULT nextval('gameplay_command_enqueue_seq_seq');

ALTER TABLE gameplay_command
    ALTER COLUMN enqueue_seq SET NOT NULL;

CREATE INDEX idx_gameplay_command_tenant_instance_enqueue_seq
    ON gameplay_command (tenant_id, game_instance_id, enqueue_seq);
