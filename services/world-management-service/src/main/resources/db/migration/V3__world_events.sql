CREATE TABLE world_event (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    region_id BIGINT REFERENCES region(id),
    event_type VARCHAR(50) NOT NULL,
    event_data TEXT,
    execute_at TIMESTAMP NOT NULL,
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP
);

ALTER TABLE region ADD COLUMN weather VARCHAR(50);
