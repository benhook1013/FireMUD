-- Preview-only containment: retain the original heap, not a copy/backfill. No UUID
-- subscription, owner, provider mapping, winner or billing sequence is inferred.
/* [jooq ignore start] */
LOCK TABLE subscription IN ACCESS EXCLUSIVE MODE;
/* [jooq ignore stop] */

ALTER TABLE subscription RENAME TO account_retained_subscription_evidence;
-- PostgreSQL-generated object names are absent from the jOOQ/H2 schema simulator.
/* [jooq ignore start] */
ALTER TABLE account_retained_subscription_evidence
    RENAME CONSTRAINT subscription_pkey TO account_retained_subscription_evidence_pkey;
ALTER TABLE account_retained_subscription_evidence
    RENAME CONSTRAINT subscription_account_id_fkey TO account_retained_subscription_evidence_account_fk;
ALTER TABLE account_retained_subscription_evidence
    RENAME CONSTRAINT subscription_entitlement_version_positive TO account_retained_subscription_evidence_version_positive;
ALTER TABLE account_retained_subscription_evidence
    RENAME CONSTRAINT subscription_tenant_authority_generation_non_nil TO account_retained_subscription_evidence_generation_non_nil;
ALTER INDEX idx_subscription_account_id RENAME TO idx_account_retained_subscription_evidence_account;
ALTER INDEX idx_subscription_tenant_id RENAME TO idx_account_retained_subscription_evidence_tenant;
ALTER SEQUENCE subscription_id_seq RENAME TO account_retained_subscription_evidence_id_seq;
/* [jooq ignore stop] */

/* [jooq ignore start] */
DROP TRIGGER subscription_authority_generation_advance ON account_retained_subscription_evidence;
-- RESTRICT is deliberate: an unexpected user of the obsolete function aborts
-- this whole transactional migration, including the rename, without row changes.
DROP FUNCTION advance_subscription_authority_generation() RESTRICT;

CREATE FUNCTION account_retained_subscription_evidence_refuse_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Retained subscription evidence is immutable and non-authorizing'
        USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_retained_subscription_evidence_immutable
    BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON account_retained_subscription_evidence
    FOR EACH STATEMENT EXECUTE FUNCTION account_retained_subscription_evidence_refuse_mutation();
ALTER TABLE account_retained_subscription_evidence
    ENABLE ALWAYS TRIGGER account_retained_subscription_evidence_immutable;
/* [jooq ignore stop] */

COMMENT ON TABLE account_retained_subscription_evidence IS
    'Immutable numeric historical evidence only. Not subscription, billing-owner, provider or production entitlement authority.';
