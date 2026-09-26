CREATE TABLE account_authority_generations (
    scope_kind VARCHAR(16) NOT NULL,
    issuer_id VARCHAR(512),
    account_uuid UUID,
    tenant_uuid UUID,
    generation BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_authority_generations_scope_kind_check
        CHECK (scope_kind IN ('ISSUER', 'ACCOUNT', 'TENANT', 'MEMBERSHIP')),
    CONSTRAINT account_authority_generations_positive_check
        CHECK (generation > 0 AND source_version > 0),
    CONSTRAINT account_authority_generations_scope_shape_check CHECK (
        (scope_kind = 'ISSUER' AND issuer_id IS NOT NULL AND issuer_id <> ''
            AND account_uuid IS NULL AND tenant_uuid IS NULL)
        OR (scope_kind = 'ACCOUNT' AND issuer_id IS NULL
            AND account_uuid IS NOT NULL AND tenant_uuid IS NULL)
        OR (scope_kind = 'TENANT' AND issuer_id IS NULL
            AND account_uuid IS NULL AND tenant_uuid IS NOT NULL)
        OR (scope_kind = 'MEMBERSHIP' AND issuer_id IS NULL
            AND account_uuid IS NOT NULL AND tenant_uuid IS NOT NULL)
    ),
    CONSTRAINT account_authority_generations_account_fk
        FOREIGN KEY (account_uuid) REFERENCES accounts (account_uuid)
);

CREATE UNIQUE INDEX account_authority_generations_issuer_scope_uq
    ON account_authority_generations (issuer_id)
    WHERE scope_kind = 'ISSUER';
CREATE UNIQUE INDEX account_authority_generations_account_scope_uq
    ON account_authority_generations (account_uuid)
    WHERE scope_kind = 'ACCOUNT';
CREATE UNIQUE INDEX account_authority_generations_tenant_scope_uq
    ON account_authority_generations (tenant_uuid)
    WHERE scope_kind = 'TENANT';
CREATE UNIQUE INDEX account_authority_generations_membership_scope_uq
    ON account_authority_generations (account_uuid, tenant_uuid)
    WHERE scope_kind = 'MEMBERSHIP';

CREATE TABLE account_authority_issuance_fences (
    account_uuid UUID PRIMARY KEY,
    issuance_fence BIGINT NOT NULL,
    source_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_authority_issuance_fences_positive_check
        CHECK (issuance_fence > 0 AND source_version > 0),
    CONSTRAINT account_authority_issuance_fences_account_fk
        FOREIGN KEY (account_uuid) REFERENCES accounts (account_uuid)
);

-- [jooq ignore start]
CREATE FUNCTION account_authority_generation_monotonic_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.scope_kind IS DISTINCT FROM OLD.scope_kind
        OR NEW.issuer_id IS DISTINCT FROM OLD.issuer_id
        OR NEW.account_uuid IS DISTINCT FROM OLD.account_uuid
        OR NEW.tenant_uuid IS DISTINCT FROM OLD.tenant_uuid THEN
        RAISE EXCEPTION 'Account authority generation scope cannot be reassigned'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_generation_scope_immutable';
    END IF;
    IF NEW.generation <> OLD.generation + 1
        OR NEW.source_version <> OLD.source_version + 1 THEN
        RAISE EXCEPTION 'Account authority generation and source version must advance by one'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_generation_monotonic';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_authority_generations_monotonic
    BEFORE UPDATE ON account_authority_generations
    FOR EACH ROW EXECUTE FUNCTION account_authority_generation_monotonic_guard();

CREATE FUNCTION account_authority_generation_no_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account authority generation history cannot be deleted'
        USING ERRCODE = '23514', CONSTRAINT = 'account_authority_generation_no_delete';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_authority_generations_no_delete
    BEFORE DELETE ON account_authority_generations
    FOR EACH ROW EXECUTE FUNCTION account_authority_generation_no_delete();

CREATE FUNCTION account_authority_issuance_fence_monotonic_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.account_uuid IS DISTINCT FROM OLD.account_uuid THEN
        RAISE EXCEPTION 'Account authority issuance-fence scope cannot be reassigned'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_issuance_fence_scope_immutable';
    END IF;
    IF NEW.issuance_fence <> OLD.issuance_fence + 1
        OR NEW.source_version <> OLD.source_version + 1 THEN
        RAISE EXCEPTION 'Account authority issuance fence and source version must advance by one'
            USING ERRCODE = '23514', CONSTRAINT = 'account_authority_issuance_fence_monotonic';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_authority_issuance_fences_monotonic
    BEFORE UPDATE ON account_authority_issuance_fences
    FOR EACH ROW EXECUTE FUNCTION account_authority_issuance_fence_monotonic_guard();

CREATE FUNCTION account_authority_issuance_fence_no_delete()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Account authority issuance-fence history cannot be deleted'
        USING ERRCODE = '23514', CONSTRAINT = 'account_authority_issuance_fence_no_delete';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_authority_issuance_fences_no_delete
    BEFORE DELETE ON account_authority_issuance_fences
    FOR EACH ROW EXECUTE FUNCTION account_authority_issuance_fence_no_delete();
-- [jooq ignore stop]
