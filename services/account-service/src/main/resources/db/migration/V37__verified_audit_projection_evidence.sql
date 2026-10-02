ALTER TABLE account_audit_outbox
    ADD COLUMN receiver_audit_projection_version INTEGER;

ALTER TABLE account_audit_outbox
    ADD CONSTRAINT account_audit_outbox_projection_version_check
        CHECK (receiver_audit_projection_version IS NULL OR receiver_audit_projection_version = 1);
