-- One immutable first-dispatch claim for the existing Account original. A retained claim never
-- authorizes another send; timeout, expiry and missing terminal history retain that obligation.
CREATE TABLE account_tenant_creation_dispatch_intents (
    creation_operation_id UUID PRIMARY KEY
        REFERENCES account_tenant_creation_source_orders(creation_operation_id) ON DELETE RESTRICT,
    dispatch_call_id UUID NOT NULL UNIQUE CHECK (dispatch_call_id <> '00000000-0000-0000-0000-000000000000'),
    authorization_binding_payload BYTEA NOT NULL CHECK (octet_length(authorization_binding_payload) BETWEEN 1 AND 4194304),
    authorization_digest VARCHAR(71) NOT NULL
        CHECK (authorization_digest = 'sha256:' || encode(sha256(authorization_binding_payload), 'hex')),
    valid_before_epoch_millis BIGINT NOT NULL CHECK (valid_before_epoch_millis BETWEEN 1 AND 9007199254740991),
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
-- [jooq ignore start]
CREATE FUNCTION account_tenant_creation_dispatch_intent_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF extract(epoch FROM clock_timestamp()) * 1000 >= NEW.valid_before_epoch_millis
        OR NOT EXISTS (
            SELECT 1 FROM account_tenant_creation_source_orders holder
            JOIN account_tenant_creation_admission_requests request USING (creation_operation_id)
            JOIN account_tenant_creation_admissions admitted USING (creation_operation_id)
            JOIN account_recent_reauthentication_evidence recent
                ON recent.evidence_id = request.reauthentication_evidence_id
            WHERE holder.creation_operation_id = NEW.creation_operation_id
                AND holder.authorization_binding_payload = NEW.authorization_binding_payload
                AND holder.authorization_digest = NEW.authorization_digest
                AND NEW.valid_before_epoch_millis <= request.operation_deadline_epoch_millis
                AND NEW.valid_before_epoch_millis <= extract(epoch FROM recent.issued_at
                    + make_interval(secs => (recent.recent_age_seconds - recent.max_clock_skew_seconds)::DOUBLE PRECISION)) * 1000
                AND NOT EXISTS (SELECT 1 FROM account_recent_reauthentication_consumptions consumed
                    WHERE consumed.evidence_id = recent.evidence_id)
                AND NOT account_tenant_creation_source_order_is_settled(holder.creation_operation_id)
        ) THEN
        RAISE EXCEPTION 'Initial dispatch requires its exact current admitted pending original'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER account_tenant_creation_dispatch_intent_guard
    AFTER INSERT ON account_tenant_creation_dispatch_intents DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_dispatch_intent_guard();
CREATE TRIGGER account_tenant_creation_dispatch_intent_immutable
    BEFORE UPDATE OR DELETE ON account_tenant_creation_dispatch_intents
    FOR EACH ROW EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();
CREATE TRIGGER account_tenant_creation_dispatch_intent_no_truncate
    BEFORE TRUNCATE ON account_tenant_creation_dispatch_intents
    FOR EACH STATEMENT EXECUTE FUNCTION account_tenant_creation_source_order_no_mutation();
-- [jooq ignore stop]
