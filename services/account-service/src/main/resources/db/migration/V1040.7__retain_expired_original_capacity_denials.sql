-- Keep existing origin decisions and budget slots immutable while allowing the new refusal cause.
ALTER TABLE account_capacity_origin_decisions
    DROP CONSTRAINT account_capacity_origin_refusal_code_check;
ALTER TABLE account_capacity_origin_decisions
    ADD CONSTRAINT account_capacity_origin_refusal_code_check CHECK (
        refusal_code IS NULL OR (
            decision = 'NO_ACCEPT'
            AND refusal_code IN ('CAPACITY_QUOTA_EXCEEDED', 'CAPACITY_EVALUATION_EXPIRED')));
