ALTER TABLE account_tenant_membership
    DROP CONSTRAINT account_membership_provenance_check;

ALTER TABLE account_tenant_membership
    ADD CONSTRAINT account_membership_provenance_check
        CHECK (authority_provenance IN (
            'EXPLICIT_JOIN',
            'LEGACY_UNVERIFIED',
            'SEEDED_DEMO',
            'TENANT_CREATION'
        ));
