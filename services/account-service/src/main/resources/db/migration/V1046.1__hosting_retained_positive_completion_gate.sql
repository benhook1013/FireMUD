-- Refuse upgrade rather than infer payment, rewrite receipts or demote retained positives.
-- Account V1047 belongs to the downstream control-ui replay-failure budget migration.
-- [jooq ignore start]
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5s';
DO $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Retained hosting qualification requires READ COMMITTED migration isolation'
            USING ERRCODE = '23514';
    END IF;
END;
$$;
-- Serialize the inspection and trigger installation against both INSERT and UPDATE writers.
LOCK TABLE subscription IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM subscription s
        WHERE s.superseded_at IS NULL AND s.status IN ('trialing', 'active', 'past_due', 'grace')
            AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_completions c
                WHERE c.subscription_id = s.subscription_id)
    ) THEN
        RAISE EXCEPTION 'Retained positive hosting subscriptions require evidence-preserving resolution before upgrade'
            USING ERRCODE = '23514';
    END IF;
END;
$$;
CREATE FUNCTION account_hosting_positive_completion_consistency() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- Inspect the final current row, not an intermediate transition's NEW image. Completion
    -- may follow the positive update in the same owner transaction; existing exact-proof
    -- and immutable-completion guards remain authoritative and unchanged.
    IF EXISTS (
        SELECT 1 FROM subscription s
        WHERE s.subscription_id = NEW.subscription_id AND s.superseded_at IS NULL
            AND s.status IN ('trialing', 'active', 'past_due', 'grace')
            AND NOT EXISTS (SELECT 1 FROM subscription_first_invoice_completions c
                WHERE c.subscription_id = s.subscription_id)
    ) THEN
        RAISE EXCEPTION 'Current positive hosting subscription requires exact first-invoice completion'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_hosting_positive_completion_consistency
    AFTER INSERT OR UPDATE ON subscription
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_hosting_positive_completion_consistency();
-- [jooq ignore stop]
