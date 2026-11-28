-- Preserve the installed deferred inventory checks and add an expiry check for new attempts.
-- Existing reservation and evaluation rows are left intact.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_tenant_capacity_exact_usage() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE held NUMERIC; used NUMERIC; actual account_tenant_capacity_usage%ROWTYPE;
BEGIN
    SELECT * INTO actual FROM account_tenant_capacity_usage
        WHERE tenant_uuid = NEW.tenant_uuid AND owner_service = NEW.owner_service FOR UPDATE;
    SELECT COALESCE(sum(capacity_delta) FILTER (WHERE state IN ('RESERVED', 'QUARANTINED_REQUIRES_AUTHORITY_REMEDIATION')), 0),
           COALESCE(sum(capacity_delta) FILTER (WHERE state = 'FINALIZED'), 0)
      INTO held, used FROM account_tenant_capacity_reservations
      WHERE tenant_uuid = NEW.tenant_uuid AND owner_service = NEW.owner_service;
    IF actual.tenant_uuid IS NULL OR held <> actual.held_units OR used <> actual.used_units THEN
        RAISE EXCEPTION 'Capacity usage differs from its complete durable reservation inventory' USING ERRCODE = '23514';
    END IF;
    -- This runs after inventory validation for a new reservation attempt. Later
    -- recovery or settlement of the same retained attempt may occur after expiry.
    IF TG_TABLE_NAME = 'account_tenant_capacity_reservations' THEN
        IF TG_OP = 'INSERT' OR NEW.admission_attempt_id IS DISTINCT FROM OLD.admission_attempt_id THEN
            IF NOT EXISTS (
                SELECT 1 FROM account_tenant_capacity_evaluations evaluation
                WHERE evaluation.evaluation_identity = NEW.evaluation_identity
                  AND evaluation.expires_at > clock_timestamp()
            ) THEN
                RAISE EXCEPTION 'Retained capacity evaluation expired before deferred reservation validation'
                    USING ERRCODE = 'P1040';
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
-- [jooq ignore stop]
