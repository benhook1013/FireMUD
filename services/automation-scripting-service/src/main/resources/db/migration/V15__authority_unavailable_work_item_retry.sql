ALTER TABLE script_work_items
    ADD COLUMN authority_unavailable_since TIMESTAMP,
    ADD COLUMN authority_unavailable_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_eligible_at TIMESTAMP;

CREATE INDEX idx_script_work_items_retry_eligibility
    ON script_work_items (status, next_eligible_at, created_at, id);
