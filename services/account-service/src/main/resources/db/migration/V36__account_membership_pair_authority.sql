-- A never-joined pair has durable positive authority before it may report a
-- sequence-zero snapshot. This migration intentionally invents no baseline for
-- retained membership/history; Account enrolls only exact verified pairs.
CREATE TABLE account_membership_pair_authority (
    account_uuid UUID NOT NULL,
    tenant_uuid UUID NOT NULL,
    legacy_tenant_id BIGINT NOT NULL CHECK (legacy_tenant_id > 0),
    tenant_provenance_kind VARCHAR(32) NOT NULL,
    tenant_source_operation_id UUID NOT NULL,
    tenant_provenance_digest VARCHAR(71) NOT NULL,
    membership_exists BOOLEAN NOT NULL,
    membership_version BIGINT NOT NULL CHECK (membership_version > 0),
    membership_authority_generation BIGINT NOT NULL
        CHECK (membership_authority_generation > 0),
    last_event_sequence BIGINT NOT NULL CHECK (last_event_sequence >= 0),
    last_event_id VARCHAR(512),
    last_event_digest VARCHAR(71),
    last_transition_invalidated BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_membership_pair_authority_pk
        PRIMARY KEY (account_uuid, tenant_uuid),
    CONSTRAINT account_membership_pair_authority_account_fk
        FOREIGN KEY (account_uuid) REFERENCES accounts (account_uuid),
    CONSTRAINT account_membership_pair_authority_provenance_kind_check
        CHECK (tenant_provenance_kind IN ('APPROVED_RETAINED', 'FRESH_GAME_DESIGN')),
    CONSTRAINT account_membership_pair_authority_provenance_digest_check
        CHECK (tenant_provenance_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT account_membership_pair_authority_event_shape_check CHECK (
        (last_event_sequence = 0 AND NOT membership_exists
            AND last_event_id IS NULL AND last_event_digest IS NULL
            AND NOT last_transition_invalidated)
        OR (last_event_sequence > 0 AND last_event_id IS NOT NULL
            AND length(btrim(last_event_id)) > 0
            AND last_event_digest IS NOT NULL
            AND last_event_digest ~ '^sha256:[0-9a-f]{64}$')
    )
);

-- The retained numeric key is an Account-local bridge, never the canonical
-- stream identity. Its pairing must remain one-to-one for each Account UUID.
CREATE UNIQUE INDEX account_membership_pair_authority_legacy_scope_uq
    ON account_membership_pair_authority (account_uuid, legacy_tenant_id);

-- [jooq ignore start]
CREATE FUNCTION account_membership_pair_authority_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid
        OR NEW.legacy_tenant_id IS DISTINCT FROM OLD.legacy_tenant_id
        OR NEW.tenant_provenance_kind IS DISTINCT FROM OLD.tenant_provenance_kind
        OR NEW.tenant_source_operation_id IS DISTINCT FROM OLD.tenant_source_operation_id
        OR NEW.tenant_provenance_digest IS DISTINCT FROM OLD.tenant_provenance_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Account membership pair identity and provenance are immutable'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_membership_pair_authority_identity_immutable';
    END IF;
    IF NEW.membership_version <> OLD.membership_version + 1
        OR NEW.last_event_sequence <> OLD.last_event_sequence + 1
        OR (NEW.last_transition_invalidated AND NEW.membership_authority_generation <>
            OLD.membership_authority_generation + 1)
        OR (NOT NEW.last_transition_invalidated AND NEW.membership_authority_generation <>
            OLD.membership_authority_generation) THEN
        RAISE EXCEPTION 'Account membership pair transition must advance exact authority'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_membership_pair_authority_transition_monotonic';
    END IF;
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_membership_pair_authority_no_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account membership pair authority history cannot be deleted'
        USING ERRCODE = '23514',
            CONSTRAINT = 'account_membership_pair_authority_no_delete';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_membership_pair_authority_update
    BEFORE UPDATE ON account_membership_pair_authority
    FOR EACH ROW EXECUTE FUNCTION account_membership_pair_authority_update_guard();

CREATE TRIGGER account_membership_pair_authority_delete
    BEFORE DELETE ON account_membership_pair_authority
    FOR EACH ROW EXECUTE FUNCTION account_membership_pair_authority_no_delete();
-- [jooq ignore stop]
