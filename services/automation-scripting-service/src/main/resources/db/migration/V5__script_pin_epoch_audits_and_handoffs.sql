-- Persist the exact observed owner pin epoch with ingress, execution, and handoff evidence.
-- Audit rows may omit the epoch for tenant-readiness or rejected pre-pin requests; handoff rows
-- remain fail-closed at zero until an instance-scoped pin is observed.
ALTER TABLE script_event_ingress_audit
    ADD COLUMN script_pin_epoch BIGINT;

ALTER TABLE script_event_audit
    ADD COLUMN script_pin_epoch BIGINT;

ALTER TABLE script_handoff_events
    ADD COLUMN script_pin_epoch BIGINT NOT NULL DEFAULT 0;
