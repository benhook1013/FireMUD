-- Arbitrate global attempt identity before checking a new refusal's budget.
-- AFTER INSERT runs only for an inserted row, not an ON CONFLICT replay, and
-- includes the new refusal in the bounded count. Failure rolls the insert back.
-- Retain the same-value budget update to serialize same-tenant refusal writers.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION require_account_capacity_origin_refusal_budget() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE refusal_limit INTEGER; retained_count BIGINT;
BEGIN
    IF NEW.decision <> 'NO_ACCEPT' THEN RETURN NEW; END IF;
    UPDATE account_capacity_origin_refusal_budget
       SET maximum_refusals = maximum_refusals
     WHERE tenant_uuid = NEW.tenant_uuid
     RETURNING maximum_refusals INTO refusal_limit;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Capacity refusal budget is unavailable' USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO retained_count FROM (
        SELECT 1 FROM account_capacity_origin_decisions
        WHERE tenant_uuid = NEW.tenant_uuid AND decision = 'NO_ACCEPT'
        LIMIT refusal_limit::BIGINT + 1
    ) retained;
    IF retained_count > refusal_limit THEN
        RAISE EXCEPTION 'Capacity refusal budget is exhausted' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER account_capacity_origin_refusal_budget_required ON account_capacity_origin_decisions;
CREATE TRIGGER account_capacity_origin_refusal_budget_required
    AFTER INSERT ON account_capacity_origin_decisions
    FOR EACH ROW EXECUTE FUNCTION require_account_capacity_origin_refusal_budget();
-- [jooq ignore stop]
