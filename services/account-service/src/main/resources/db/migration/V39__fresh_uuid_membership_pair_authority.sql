-- The canonical tenant UUID is sufficient identity for fresh Game Design tenants.
-- Only retained associations have a private legacy numeric bridge; never invent one for fresh rows.
-- Fail before changing the table if an earlier candidate write fabricated a fresh numeric key.
-- [jooq ignore start]
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM account_membership_pair_authority
        WHERE tenant_provenance_kind = 'FRESH_GAME_DESIGN'
            AND legacy_tenant_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Fresh Account membership pair must not carry a legacy tenant ID'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_membership_pair_authority_fresh_legacy_id_migration';
    END IF;
END;
$$;
-- [jooq ignore stop]

ALTER TABLE account_membership_pair_authority
    ALTER COLUMN legacy_tenant_id DROP NOT NULL;

-- jOOQ's H2 simulator assigns a different generated name to the V36 inline CHECK.
-- Keep the PostgreSQL drop active; skip only this name-specific operation in simulation.
-- [jooq ignore start]
ALTER TABLE account_membership_pair_authority
    DROP CONSTRAINT account_membership_pair_authority_legacy_tenant_id_check;
-- [jooq ignore stop]

ALTER TABLE account_membership_pair_authority
    ADD CONSTRAINT account_membership_pair_authority_provenance_legacy_id_check CHECK (
        (tenant_provenance_kind = 'APPROVED_RETAINED'
            AND legacy_tenant_id IS NOT NULL
            AND legacy_tenant_id > 0)
        OR (tenant_provenance_kind = 'FRESH_GAME_DESIGN'
            AND legacy_tenant_id IS NULL)
    );

DROP INDEX account_membership_pair_authority_legacy_scope_uq;

-- Preserve one-to-one legacy association checks only for retained pairs.
CREATE UNIQUE INDEX account_membership_pair_authority_legacy_scope_uq
    ON account_membership_pair_authority (account_uuid, legacy_tenant_id)
    WHERE tenant_provenance_kind = 'APPROVED_RETAINED';
