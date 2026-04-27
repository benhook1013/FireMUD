ALTER TABLE runtime_region_status
    ADD COLUMN last_committed_tick_id BIGINT NOT NULL DEFAULT 0;
