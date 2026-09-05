-- Persist the Automation-owned plugin lifecycle fence with runtime state and its
-- materialized schedules.  The activation epoch is part of plugin timer candidate
-- identity; lifecycle revision remains fence evidence only.
ALTER TABLE plugin_runtime_states
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE plugin_runtime_states
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_schedule_instances
    ADD COLUMN plugin_activation_epoch BIGINT NOT NULL DEFAULT 0;

ALTER TABLE script_schedule_instances
    ADD COLUMN lifecycle_revision BIGINT NOT NULL DEFAULT 0;
