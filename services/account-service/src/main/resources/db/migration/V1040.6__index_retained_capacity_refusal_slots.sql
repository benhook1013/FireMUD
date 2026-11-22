-- Keep refusal admission independent of the number of retained origin rows.
-- Serialize this backfill with origin writes and preserve every original row.
-- [jooq ignore start]
LOCK TABLE account_capacity_origin_decisions IN SHARE ROW EXCLUSIVE MODE;
-- [jooq ignore stop]

CREATE TABLE account_capacity_origin_refusal_slots (
    tenant_uuid UUID NOT NULL,
    slot BIGINT NOT NULL CHECK (slot > 0),
    decision_id UUID NOT NULL UNIQUE REFERENCES account_capacity_origin_decisions(decision_id),
    PRIMARY KEY (tenant_uuid, slot)
);

-- [jooq ignore start]
INSERT INTO account_capacity_origin_refusal_slots (tenant_uuid, slot, decision_id)
SELECT tenant_uuid, row_number() OVER (PARTITION BY tenant_uuid ORDER BY decision_id), decision_id
FROM account_capacity_origin_decisions
WHERE decision = 'NO_ACCEPT';

CREATE FUNCTION guard_account_capacity_origin_refusal_slot_source() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM account_capacity_origin_decisions
        WHERE decision_id = NEW.decision_id AND tenant_uuid = NEW.tenant_uuid AND decision = 'NO_ACCEPT'
    ) THEN
        RAISE EXCEPTION 'Capacity refusal slot requires its original tenant refusal' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_capacity_origin_refusal_slot_source
    BEFORE INSERT ON account_capacity_origin_refusal_slots
    FOR EACH ROW EXECUTE FUNCTION guard_account_capacity_origin_refusal_slot_source();

CREATE TRIGGER account_capacity_origin_refusal_slot_immutable
    BEFORE UPDATE OR DELETE ON account_capacity_origin_refusal_slots
    FOR EACH ROW EXECUTE FUNCTION account_capacity_origin_decision_immutable();

CREATE TRIGGER account_capacity_origin_refusal_slots_no_truncate
    BEFORE TRUNCATE ON account_capacity_origin_refusal_slots
    FOR EACH STATEMENT EXECUTE FUNCTION account_capacity_origin_decision_immutable();

CREATE OR REPLACE FUNCTION require_account_capacity_origin_refusal_budget() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE refusal_limit INTEGER; last_slot BIGINT;
BEGIN
    IF NEW.decision <> 'NO_ACCEPT' THEN RETURN NEW; END IF;

    -- Keep the same serializable tenant guard and immutable provisioned limit.
    UPDATE account_capacity_origin_refusal_budget
       SET maximum_refusals = maximum_refusals
     WHERE tenant_uuid = NEW.tenant_uuid
     RETURNING maximum_refusals INTO refusal_limit;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Capacity refusal budget is unavailable' USING ERRCODE = '23514';
    END IF;

    SELECT COALESCE((
        SELECT slot
        FROM account_capacity_origin_refusal_slots
        WHERE tenant_uuid = NEW.tenant_uuid
        ORDER BY slot DESC
        LIMIT 1
    ), 0) INTO last_slot;
    IF last_slot >= refusal_limit THEN
        RAISE EXCEPTION 'Capacity refusal budget is exhausted' USING ERRCODE = '23514';
    END IF;

    -- The existing AFTER INSERT trigger runs only after origin identity wins.
    INSERT INTO account_capacity_origin_refusal_slots (tenant_uuid, slot, decision_id)
    VALUES (NEW.tenant_uuid, last_slot + 1, NEW.decision_id);
    RETURN NEW;
END;
$$;
-- [jooq ignore stop]
