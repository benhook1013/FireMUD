ALTER TABLE world_instance
    ADD COLUMN termination_request_id VARCHAR(128),
    ADD COLUMN terminated_at TIMESTAMP;
