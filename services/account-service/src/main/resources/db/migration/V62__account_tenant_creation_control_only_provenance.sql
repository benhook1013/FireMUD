-- Creator bootstrap is a distinct control-only membership provenance. The
-- owner producer remains responsible for proving its source and membership
-- state; this storage constraint only prevents it from admitting gameplay.
ALTER TABLE account_tenant_membership
    DROP CONSTRAINT account_membership_provenance_check;

ALTER TABLE account_tenant_membership
    ADD CONSTRAINT account_membership_provenance_check
        CHECK (authority_provenance IN (
            'EXPLICIT_JOIN', 'LEGACY_UNVERIFIED', 'SEEDED_DEMO', 'TENANT_CREATION'));

ALTER TABLE account_tenant_membership
    ADD CONSTRAINT account_membership_tenant_creation_not_admitting_check
        CHECK (authority_provenance <> 'TENANT_CREATION'
            OR gameplay_admission_allowed = FALSE);
