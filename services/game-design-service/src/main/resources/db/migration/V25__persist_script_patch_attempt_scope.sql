ALTER TABLE publish_attempt
    ADD COLUMN base_version_id BIGINT,
    ADD COLUMN request_digest VARCHAR(64);
