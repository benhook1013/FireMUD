ALTER TABLE plugin_runtime_states
    ADD COLUMN last_policy_checked_at TIMESTAMP NOT NULL DEFAULT TIMESTAMP '1970-01-01 00:00:00';
