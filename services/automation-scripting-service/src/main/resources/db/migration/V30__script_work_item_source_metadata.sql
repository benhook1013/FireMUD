ALTER TABLE script_event_ingress_audit
    ADD COLUMN source_kind VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN source_state VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN source_ordinal BIGINT,
    ADD COLUMN source_due_tick_id BIGINT,
    ADD COLUMN source_due_at_ms BIGINT;

ALTER TABLE script_work_items
    ADD COLUMN source_kind VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN source_state VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN source_ordinal BIGINT,
    ADD COLUMN source_due_tick_id BIGINT,
    ADD COLUMN source_due_at_ms BIGINT;

ALTER TABLE script_event_audit
    ADD COLUMN source_kind VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN source_state VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN source_ordinal BIGINT,
    ADD COLUMN source_due_tick_id BIGINT,
    ADD COLUMN source_due_at_ms BIGINT;

ALTER TABLE script_handoff_events
    ADD COLUMN source_kind VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN source_state VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN source_ordinal BIGINT,
    ADD COLUMN source_due_tick_id BIGINT,
    ADD COLUMN source_due_at_ms BIGINT;
