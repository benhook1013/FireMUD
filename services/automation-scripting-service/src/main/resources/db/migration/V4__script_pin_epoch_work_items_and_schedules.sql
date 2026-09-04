-- Persist the owner-issued script pin epoch on instance-scoped work and schedule state.
-- Zero is the fail-closed representation for rows that have not observed an owner pin.
ALTER TABLE script_work_items
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_schedule_instances
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;
