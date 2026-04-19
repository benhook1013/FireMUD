DROP TABLE IF EXISTS world_event;

CREATE TABLE world_event (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    region_instance_id BIGINT REFERENCES region_instance(id),
    event_type VARCHAR(50) NOT NULL,
    event_data TEXT,
    execute_at TIMESTAMP NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at TIMESTAMP,
    version INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_world_event_due_instance ON world_event(processed, execute_at, game_instance_id);
CREATE INDEX idx_world_event_region_instance ON world_event(region_instance_id);
