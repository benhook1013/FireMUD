-- PostgreSQL enforces this check for new or updated rows without scanning retained rows.
-- jOOQ excludes only PostgreSQL's NOT VALID flag and retains the check expression.
ALTER TABLE log_events
    ADD CONSTRAINT chk_log_events_tenant_audit_positive_tenant_id
        CHECK (scope <> 'tenant' OR audit_event_id IS NULL OR tenant_id > 0) /* [jooq ignore start] */ NOT VALID /* [jooq ignore stop] */;
