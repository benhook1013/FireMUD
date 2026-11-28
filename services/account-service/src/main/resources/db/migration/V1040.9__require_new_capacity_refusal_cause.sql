-- Preserve older refusal rows without guessing why they were recorded.
-- PostgreSQL still enforces a NOT VALID check on new writes.
-- [jooq ignore start]
ALTER TABLE account_capacity_origin_decisions
    ADD CONSTRAINT account_capacity_origin_refusal_cause_required
    CHECK (decision <> 'NO_ACCEPT' OR refusal_code IS NOT NULL) NOT VALID;
-- [jooq ignore stop]
