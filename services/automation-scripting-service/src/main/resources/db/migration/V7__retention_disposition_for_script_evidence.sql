-- Owner-local retention metadata for the scripting high-churn families.
-- A hold is an explicit safety/governance fence; NULL means no hold.  Cleanup
-- may remove evidence only after the configured safe watermark/age and after
-- the hold has expired.  The tenant key is retained on every family so a
-- disposition can never cross an owner boundary.
ALTER TABLE script_event_audit
    ADD COLUMN retention_hold_until TIMESTAMPTZ NULL;
ALTER TABLE script_handoff_events
    ADD COLUMN retention_hold_until TIMESTAMPTZ NULL;
ALTER TABLE script_dead_letter_replay_requests
    ADD COLUMN retention_hold_until TIMESTAMPTZ NULL;
ALTER TABLE script_dead_letter_replay_results
    ADD COLUMN retention_hold_until TIMESTAMPTZ NULL;

CREATE INDEX idx_script_event_audit_retention
    ON script_event_audit (updated_at, retention_hold_until, tenant_id);
CREATE INDEX idx_script_handoff_events_retention
    ON script_handoff_events (observed_at, retention_hold_until, tenant_id);
CREATE INDEX idx_script_dead_letter_replay_requests_retention
    ON script_dead_letter_replay_requests (updated_at, retention_hold_until, tenant_id);
CREATE INDEX idx_script_dead_letter_replay_results_retention
    ON script_dead_letter_replay_results (created_at, retention_hold_until, tenant_id);
