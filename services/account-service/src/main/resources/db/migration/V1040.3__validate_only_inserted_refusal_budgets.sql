-- Check historical retained evidence only when a budget is actually inserted.
-- BEFORE INSERT also runs for ON CONFLICT DO NOTHING and must not mask the
-- repository's immutable-limit IDEMPOTENCY_CONFLICT result.
-- [jooq ignore start]
DROP TRIGGER account_capacity_origin_refusal_budget_guard
    ON account_capacity_origin_refusal_budget;
CREATE TRIGGER account_capacity_origin_refusal_budget_guard
    BEFORE UPDATE OR DELETE ON account_capacity_origin_refusal_budget
    FOR EACH ROW EXECUTE FUNCTION guard_account_capacity_origin_refusal_budget();
CREATE TRIGGER account_capacity_origin_refusal_budget_insert_guard
    AFTER INSERT ON account_capacity_origin_refusal_budget
    FOR EACH ROW EXECUTE FUNCTION guard_account_capacity_origin_refusal_budget();
-- [jooq ignore stop]
