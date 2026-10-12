-- Forward correction for the published V137 restriction receipts.
-- The exact current Account security-lock projection is the lifecycle authority when it exists.
-- Historical births and accounts without a current projection remain unavailable, not cleared.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_platform_restriction_sync_security_lock_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    event_row RECORD;
    event_payload JSONB;
    operation_row account_platform_restriction_operations%ROWTYPE;
BEGIN
    IF NEW.scope_kind IS DISTINCT FROM 'ACCOUNT'
        OR NEW.last_outbox_sequence = OLD.last_outbox_sequence THEN
        RETURN NULL;
    END IF;
    SELECT payload INTO event_row FROM account_authority_outbox_events
        WHERE outbox_stream_key = NEW.outbox_stream_key
          AND outbox_sequence = NEW.last_outbox_sequence;
    IF event_row.payload IS NULL THEN RETURN NULL; END IF;
    event_payload := convert_from(event_row.payload, 'UTF8')::JSONB;
    IF event_payload->>'eventType' IS DISTINCT FROM 'ACCOUNT_RESTRICTION_CHANGED'
        OR event_payload->>'restrictionCategory' IS DISTINCT FROM 'account_security_lock' THEN
        RETURN NULL;
    END IF;
    operation_row := account_platform_restriction_require_source_event(
        NEW, event_payload, event_row.payload);
    IF operation_row.result_state = 'RESTRICTED' THEN
        UPDATE accounts SET lifecycle_state = 'security_locked'
            WHERE account_uuid = NEW.account_uuid AND lifecycle_state = 'active';
        IF NOT EXISTS (
            SELECT 1 FROM accounts
            WHERE account_uuid = NEW.account_uuid AND lifecycle_state = 'security_locked') THEN
            RAISE EXCEPTION 'Restricted Account security projection requires a locked lifecycle'
                USING ERRCODE = '23514',
                    CONSTRAINT = 'account_platform_restriction_security_lock_lifecycle';
        END IF;
    ELSE
        UPDATE accounts SET lifecycle_state = 'active'
            WHERE account_uuid = NEW.account_uuid AND lifecycle_state = 'security_locked';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION account_platform_restriction_require_final_lifecycle(
    account_uuid_value UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    lifecycle_value TEXT;
    restriction_state_value TEXT;
BEGIN
    -- The account row is the serialization point shared by lifecycle and current-projection checks.
    SELECT lifecycle_state INTO lifecycle_value
        FROM accounts WHERE account_uuid = account_uuid_value FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;

    SELECT restriction_state INTO restriction_state_value
        FROM account_platform_restriction_current_projections
        WHERE account_uuid = account_uuid_value
          AND category = 'account_security_lock';
    IF NOT FOUND THEN RETURN; END IF;

    IF (restriction_state_value = 'RESTRICTED')
        IS DISTINCT FROM (lifecycle_value = 'security_locked') THEN
        RAISE EXCEPTION 'Current Account security-lock projection and lifecycle disagree'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_platform_restriction_security_lock_lifecycle';
    END IF;
END;
$$;

CREATE FUNCTION account_platform_restriction_final_lifecycle_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    account_uuid_value UUID;
BEGIN
    IF TG_TABLE_NAME = 'accounts' THEN
        account_uuid_value := NEW.account_uuid;
    ELSE
        IF TG_OP = 'INSERT' THEN
            IF NEW.category IS DISTINCT FROM 'account_security_lock' THEN
                RETURN NULL;
            END IF;
        ELSIF NEW.category IS DISTINCT FROM 'account_security_lock'
            AND OLD.category IS DISTINCT FROM 'account_security_lock' THEN
            RETURN NULL;
        END IF;
        account_uuid_value := NEW.account_uuid;
    END IF;

    PERFORM account_platform_restriction_require_final_lifecycle(account_uuid_value);
    RETURN NULL;
END;
$$;

-- Run at transaction completion so an authorized transaction is checked against its final rows.
-- The existing V79/V80 deferred row-image receipt guards continue to validate every mutation.
CREATE CONSTRAINT TRIGGER account_platform_restriction_account_lifecycle_consistency
    AFTER UPDATE OF lifecycle_state ON accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_platform_restriction_final_lifecycle_guard();

CREATE CONSTRAINT TRIGGER account_platform_restriction_current_lifecycle_consistency
    AFTER INSERT OR UPDATE ON account_platform_restriction_current_projections
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_platform_restriction_final_lifecycle_guard();
-- [jooq ignore end]
