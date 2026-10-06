ALTER TABLE account_realm_access_grant
    ADD COLUMN granted BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN grant_authority_generation UUID;

UPDATE account_realm_access_grant
SET grant_authority_generation = gen_random_uuid();

ALTER TABLE account_realm_access_grant
    ALTER COLUMN grant_authority_generation SET NOT NULL;
ALTER TABLE account_realm_access_grant
    ALTER COLUMN grant_authority_generation SET DEFAULT gen_random_uuid();
ALTER TABLE account_realm_access_grant
    ADD CONSTRAINT account_realm_access_grant_version_positive CHECK (grant_version > 0);
ALTER TABLE account_realm_access_grant
    ADD CONSTRAINT account_realm_access_grant_generation_non_nil
        CHECK (grant_authority_generation <> '00000000-0000-0000-0000-000000000000'::UUID);

/* [jooq ignore start] */
CREATE FUNCTION advance_realm_grant_authority() RETURNS trigger AS $$
BEGIN
    NEW.grant_version := OLD.grant_version + 1;
    NEW.grant_authority_generation := gen_random_uuid();
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_realm_access_grant_authority_advance
    BEFORE UPDATE ON account_realm_access_grant
    FOR EACH ROW EXECUTE FUNCTION advance_realm_grant_authority();
/* [jooq ignore stop] */
