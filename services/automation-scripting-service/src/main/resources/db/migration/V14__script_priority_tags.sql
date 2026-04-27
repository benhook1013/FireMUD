ALTER TABLE script_event_bindings
    ADD COLUMN priority_tag VARCHAR(32) NOT NULL DEFAULT 'normal';

ALTER TABLE script_work_items
    ADD COLUMN priority_tag VARCHAR(32) NOT NULL DEFAULT 'normal';
