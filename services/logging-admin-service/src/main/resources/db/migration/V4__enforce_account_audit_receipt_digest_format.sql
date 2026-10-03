-- PostgreSQL enforces this check on new or updated rows without scanning retained rows.
-- jOOQ excludes only PostgreSQL's NOT VALID flag and retains the check expression.
ALTER TABLE account_audit_receipts
    ADD CONSTRAINT chk_account_audit_receipt_digest_format
        CHECK (payload_digest ~ '^sha256:[0-9a-f]{64}$') /* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */;
