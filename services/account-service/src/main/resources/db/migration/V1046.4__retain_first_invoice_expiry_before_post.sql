CREATE TABLE subscription_first_invoice_expired_before_post (
    request_id UUID PRIMARY KEY REFERENCES subscription_first_invoice_command_claims(request_id) ON DELETE RESTRICT,
    observed_at TIMESTAMPTZ NOT NULL
);

-- [jooq ignore start]
CREATE FUNCTION account_first_invoice_expired_before_post_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE claim_transaction BIGINT; authorization_expiry TIMESTAMPTZ;
BEGIN
    SELECT c.created_transaction_id, a.valid_until INTO STRICT claim_transaction, authorization_expiry
    FROM subscription_first_invoice_command_claims c
    JOIN subscription_first_invoice_authorizations a USING (authorization_request_id, request_id, subscription_id)
    WHERE c.request_id = NEW.request_id;
    IF claim_transaction = txid_current() OR NEW.observed_at < authorization_expiry THEN
        RAISE EXCEPTION 'First-invoice no-send evidence requires an independently committed claim and expired authorization' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER first_invoice_expired_before_post_insert BEFORE INSERT ON subscription_first_invoice_expired_before_post
    FOR EACH ROW EXECUTE FUNCTION account_first_invoice_expired_before_post_guard();
CREATE TRIGGER first_invoice_expired_before_post_immutable BEFORE UPDATE OR DELETE ON subscription_first_invoice_expired_before_post
    FOR EACH ROW EXECUTE FUNCTION account_subscription_no_mutation();
CREATE TRIGGER first_invoice_expired_before_post_no_truncate BEFORE TRUNCATE ON subscription_first_invoice_expired_before_post
    FOR EACH STATEMENT EXECUTE FUNCTION account_subscription_no_mutation();
-- [jooq ignore stop]
