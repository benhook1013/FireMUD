-- Add a write-time fence without rewriting retained reservations, evaluations or prior guards.
-- Expiry never releases a hold. Existing attempt replay and recovery remain valid after expiry.
-- [jooq ignore start]
CREATE FUNCTION account_tenant_capacity_attempt_freshness_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    evaluation account_tenant_capacity_evaluations%ROWTYPE;
    observed_at TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.admission_attempt_id IS NOT DISTINCT FROM OLD.admission_attempt_id THEN
        RETURN NEW;
    END IF;
    SELECT * INTO evaluation FROM account_tenant_capacity_evaluations
        WHERE evaluation_identity = NEW.evaluation_identity FOR SHARE;
    -- Capture only after the evaluation lock. CURRENT_TIMESTAMP is frozen before lock waits.
    observed_at := clock_timestamp();
    IF NOT FOUND OR evaluation.evaluated_at > observed_at
        OR evaluation.expires_at <= observed_at OR NEW.expires_at <= observed_at THEN
        RAISE EXCEPTION 'Capacity admission attempt is stale at the reservation write boundary'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_tenant_capacity_attempt_freshness
    AFTER INSERT OR UPDATE ON account_tenant_capacity_reservations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_tenant_capacity_attempt_freshness_guard();
-- [jooq ignore stop]
