ALTER TABLE subscription
    ADD COLUMN tenant_authority_generation UUID;

UPDATE subscription
SET tenant_authority_generation = gen_random_uuid();

ALTER TABLE subscription
    ALTER COLUMN tenant_authority_generation SET NOT NULL;
ALTER TABLE subscription
    ALTER COLUMN tenant_authority_generation SET DEFAULT gen_random_uuid();
ALTER TABLE subscription
    ADD CONSTRAINT subscription_tenant_authority_generation_non_nil
        CHECK (tenant_authority_generation <> '00000000-0000-0000-0000-000000000000'::UUID);

/* [jooq ignore start] */
CREATE FUNCTION advance_subscription_authority_generation() RETURNS trigger AS $$
BEGIN
    NEW.entitlement_version := OLD.entitlement_version + 1;
    NEW.tenant_authority_generation := gen_random_uuid();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER subscription_authority_generation_advance
    BEFORE UPDATE ON subscription
    FOR EACH ROW EXECUTE FUNCTION advance_subscription_authority_generation();
/* [jooq ignore stop] */
