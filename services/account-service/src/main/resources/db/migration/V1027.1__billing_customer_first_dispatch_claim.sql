-- A first customer dispatch is committed before provider IO. An existing claim never authorizes
-- another create: empty eventual-consistency search, crashes and key expiry remain unresolved.
ALTER TABLE billing_customer ADD CONSTRAINT billing_customer_dispatch_binding UNIQUE
    (account_uuid, customer_provisioning_request_id, provider_idempotency_id);

CREATE TABLE account_billing_customer_dispatch_claim (
    account_uuid UUID PRIMARY KEY,
    customer_provisioning_request_id UUID NOT NULL,
    provider_idempotency_id UUID NOT NULL,
    claim_id UUID NOT NULL UNIQUE CHECK (claim_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT billing_customer_dispatch_exact_owner FOREIGN KEY
        (account_uuid, customer_provisioning_request_id, provider_idempotency_id)
        REFERENCES billing_customer
        (account_uuid, customer_provisioning_request_id, provider_idempotency_id) ON DELETE RESTRICT
);

-- [jooq ignore start]
CREATE FUNCTION account_billing_customer_dispatch_insert_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    owner_row billing_customer%ROWTYPE;
BEGIN
    -- Preserve the Account-first lock order used by all billing prerequisite writers.
    PERFORM 1 FROM accounts WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    SELECT * INTO STRICT owner_row FROM billing_customer
        WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    IF owner_row.state = 'CONFIRMED'
        OR (NEW.customer_provisioning_request_id, NEW.provider_idempotency_id)
            IS DISTINCT FROM
            (owner_row.customer_provisioning_request_id, owner_row.provider_idempotency_id)
        OR NEW.claim_id IN (NEW.customer_provisioning_request_id, NEW.provider_idempotency_id) THEN
        RAISE EXCEPTION 'Customer dispatch requires exact unresolved durable owner' USING ERRCODE = '23514';
    END IF;
    NEW.claimed_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER billing_customer_dispatch_insert_guard
    BEFORE INSERT ON account_billing_customer_dispatch_claim
    FOR EACH ROW EXECUTE FUNCTION account_billing_customer_dispatch_insert_guard();
CREATE TRIGGER billing_customer_dispatch_immutable
    BEFORE UPDATE OR DELETE ON account_billing_customer_dispatch_claim
    FOR EACH ROW EXECUTE FUNCTION account_billing_prerequisite_no_erasure();
CREATE TRIGGER billing_customer_dispatch_no_truncate
    BEFORE TRUNCATE ON account_billing_customer_dispatch_claim
    FOR EACH STATEMENT EXECUTE FUNCTION account_billing_prerequisite_no_erasure();
-- [jooq ignore stop]
