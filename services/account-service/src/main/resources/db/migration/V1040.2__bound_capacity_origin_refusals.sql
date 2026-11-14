-- Refusal capacity is provisioned explicitly per tenant. Existing decisions remain immutable.
CREATE TABLE account_capacity_origin_refusal_budget (
    tenant_uuid UUID PRIMARY KEY
        REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    maximum_refusals INTEGER NOT NULL CHECK (maximum_refusals > 0)
);

ALTER TABLE account_capacity_origin_decisions
    ADD COLUMN refusal_code TEXT,
    ADD CONSTRAINT account_capacity_origin_refusal_code_check CHECK (
        refusal_code IS NULL OR (
            decision = 'NO_ACCEPT' AND refusal_code = 'CAPACITY_QUOTA_EXCEEDED'));

CREATE INDEX account_capacity_origin_refusals_by_tenant
    ON account_capacity_origin_decisions (tenant_uuid, request_id)
    WHERE decision = 'NO_ACCEPT';

-- [jooq ignore start]
CREATE FUNCTION guard_account_capacity_origin_refusal_budget() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE retained_count BIGINT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT count(*) INTO retained_count FROM (
            SELECT 1 FROM account_capacity_origin_decisions
            WHERE tenant_uuid = NEW.tenant_uuid AND decision = 'NO_ACCEPT'
            LIMIT NEW.maximum_refusals::BIGINT + 1
        ) retained;
        IF retained_count > NEW.maximum_refusals THEN
            RAISE EXCEPTION 'Capacity refusal budget cannot exclude retained evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- A same-value update serializes competing refusal writers under SERIALIZABLE.
        IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    END IF;
    RAISE EXCEPTION 'Capacity refusal budget is immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER account_capacity_origin_refusal_budget_guard
    BEFORE INSERT OR UPDATE OR DELETE ON account_capacity_origin_refusal_budget
    FOR EACH ROW EXECUTE FUNCTION guard_account_capacity_origin_refusal_budget();
CREATE TRIGGER account_capacity_origin_refusal_budget_no_truncate
    BEFORE TRUNCATE ON account_capacity_origin_refusal_budget
    FOR EACH STATEMENT EXECUTE FUNCTION account_capacity_origin_decision_immutable();

CREATE FUNCTION require_account_capacity_origin_refusal_budget() RETURNS TRIGGER
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
        LIMIT refusal_limit
    ) retained;
    IF retained_count >= refusal_limit THEN
        RAISE EXCEPTION 'Capacity refusal budget is exhausted' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_capacity_origin_refusal_budget_required
    BEFORE INSERT ON account_capacity_origin_decisions
    FOR EACH ROW EXECUTE FUNCTION require_account_capacity_origin_refusal_budget();
-- [jooq ignore stop]
