-- Backend preparation only: no route, mounted secret, issuer bean or production activation.
-- Credentials belong only to the separate encrypted response table, never to owner evidence.
CREATE SEQUENCE account_control_ui_source_fence START WITH 1;
CREATE TABLE account_control_ui_issuance_operations (
    request_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL UNIQUE,
    token_jti UUID NOT NULL UNIQUE,
    account_uuid UUID NOT NULL REFERENCES accounts(account_uuid),
    tenant_uuid UUID NOT NULL REFERENCES account_canonical_tenant_identity_claims(canonical_tenant_id),
    caller_workload VARCHAR(256) NOT NULL,
    caller_context_id UUID NOT NULL,
    request_mac_key_id VARCHAR(32) NOT NULL,
    request_digest VARCHAR(64) NOT NULL CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    claims_payload BYTEA NOT NULL CHECK (octet_length(claims_payload) BETWEEN 1 AND 16384),
    source_payload BYTEA NOT NULL CHECK (octet_length(source_payload) BETWEEN 1 AND 131072),
    bundle_payload BYTEA NOT NULL CHECK (octet_length(bundle_payload) BETWEEN 1 AND 131072),
    signer_receipt BYTEA NOT NULL CHECK (octet_length(signer_receipt) BETWEEN 1 AND 65536),
    issued_at_epoch_second BIGINT NOT NULL CHECK (issued_at_epoch_second > 0),
    expires_at_epoch_second BIGINT NOT NULL,
    recovery_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PREPARED', 'CANDIDATE', 'COMMITTED', 'FAILED', 'REVOKING', 'REVOKED')),
    token_hash VARCHAR(64) UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    pending_registry BYTEA,
    active_registry BYTEA,
    pending_receipt BYTEA,
    committed_at TIMESTAMPTZ,
    recovery_failed_at TIMESTAMPTZ,
    revocation_receipt BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (expires_at_epoch_second > issued_at_epoch_second
        AND expires_at_epoch_second <= issued_at_epoch_second + 300),
    CHECK ((status IN ('PREPARED','FAILED') AND token_hash IS NULL AND pending_registry IS NULL
            AND active_registry IS NULL AND pending_receipt IS NULL AND committed_at IS NULL)
        OR (status = 'CANDIDATE' AND token_hash IS NOT NULL AND pending_registry IS NOT NULL
            AND active_registry IS NOT NULL AND pending_receipt IS NULL AND committed_at IS NULL)
        OR (status = 'COMMITTED' AND token_hash IS NOT NULL AND pending_registry IS NOT NULL
            AND active_registry IS NOT NULL AND pending_receipt IS NOT NULL AND committed_at IS NOT NULL)
        OR (status IN ('REVOKING','REVOKED') AND token_hash IS NOT NULL AND pending_registry IS NOT NULL
            AND active_registry IS NOT NULL
            AND ((pending_receipt IS NULL AND committed_at IS NULL)
                OR (pending_receipt IS NOT NULL AND committed_at IS NOT NULL)))),
    CHECK ((status IN ('FAILED','REVOKING','REVOKED')) = (recovery_failed_at IS NOT NULL)),
    CHECK ((status = 'REVOKED') = (revocation_receipt IS NOT NULL))
);

CREATE TABLE account_control_ui_response_envelopes (
    operation_id UUID PRIMARY KEY REFERENCES account_control_ui_issuance_operations(operation_id),
    encrypted_response BYTEA NOT NULL CHECK (octet_length(encrypted_response) BETWEEN 1 AND 65536),
    owner_binding BYTEA NOT NULL CHECK (octet_length(owner_binding) BETWEEN 1 AND 8192),
    recovery_expires_at TIMESTAMPTZ NOT NULL
);

-- [jooq ignore start]
ALTER TABLE account_control_ui_issuance_operations
    ADD CONSTRAINT account_control_ui_recovery_expiry_bound CHECK (
        recovery_expires_at > to_timestamp(issued_at_epoch_second)
        AND recovery_expires_at <= to_timestamp(issued_at_epoch_second + 60)
        AND recovery_expires_at <= to_timestamp(expires_at_epoch_second));

CREATE FUNCTION account_control_ui_issuance_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Control-ui issuance evidence cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status','token_hash','pending_registry','active_registry',
            'pending_receipt','committed_at','recovery_failed_at','revocation_receipt']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','token_hash','pending_registry','active_registry',
            'pending_receipt','committed_at','recovery_failed_at','revocation_receipt'])
        OR NOT ((OLD.status = 'PREPARED' AND NEW.status = 'CANDIDATE')
            OR (OLD.status = 'CANDIDATE' AND NEW.status = 'COMMITTED')
            OR (OLD.status = 'PREPARED' AND NEW.status = 'FAILED')
            OR (OLD.status IN ('CANDIDATE','COMMITTED') AND NEW.status = 'REVOKING')
            OR (OLD.status = 'REVOKING' AND NEW.status = 'REVOKED'))
        OR (OLD.status <> 'PREPARED' AND
            (NEW.token_hash IS DISTINCT FROM OLD.token_hash
            OR NEW.pending_registry IS DISTINCT FROM OLD.pending_registry
            OR NEW.active_registry IS DISTINCT FROM OLD.active_registry))
        OR (NEW.status IN ('REVOKING','REVOKED') AND
            (NEW.pending_receipt IS DISTINCT FROM OLD.pending_receipt
            OR NEW.committed_at IS DISTINCT FROM OLD.committed_at))
        OR (OLD.status = 'REVOKING' AND NEW.recovery_failed_at IS DISTINCT FROM OLD.recovery_failed_at) THEN
        RAISE EXCEPTION 'Control-ui original operation or candidate cannot change' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'COMMITTED' AND NOT EXISTS (
        SELECT 1 FROM account_control_ui_response_envelopes e
        WHERE e.operation_id = NEW.operation_id AND e.recovery_expires_at = NEW.recovery_expires_at) THEN
        RAISE EXCEPTION 'Protected original control-ui response is absent' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_control_ui_issuance_immutable
    BEFORE UPDATE OR DELETE ON account_control_ui_issuance_operations
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_issuance_guard();
CREATE TRIGGER account_control_ui_response_immutable
    BEFORE UPDATE OR DELETE ON account_control_ui_response_envelopes
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_control_ui_issuance_no_truncate
    BEFORE TRUNCATE ON account_control_ui_issuance_operations
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();
CREATE TRIGGER account_control_ui_response_no_truncate
    BEFORE TRUNCATE ON account_control_ui_response_envelopes
    FOR EACH STATEMENT EXECUTE FUNCTION account_draft_authorization_immutable_guard();

-- Derive exact source keys from owner rows, not from caller assertions or broad tenant matching.
-- These keys identify serialization only and do not provision authority/baselines.
CREATE FUNCTION account_control_ui_source_keys(table_name TEXT, row_value JSONB)
RETURNS TEXT[] LANGUAGE plpgsql STABLE STRICT AS $$
DECLARE
    account_value TEXT := row_value->>'account_uuid';
    tenant_value TEXT := row_value->>'tenant_uuid';
    scope_kind TEXT;
    member RECORD;
BEGIN
    CASE table_name
    WHEN 'accounts' THEN
        RETURN ARRAY['ACCOUNT:' || account_value, 'GLOBAL_ROLES:' || account_value];
    WHEN 'account_authority_generations' THEN
        scope_kind := row_value->>'scope_kind';
        IF scope_kind = 'ISSUER' THEN RETURN ARRAY['ISSUER:' || (row_value->>'issuer_id')]; END IF;
        IF scope_kind = 'TENANT' THEN RETURN ARRAY['TENANT:' || tenant_value]; END IF;
        IF scope_kind = 'MEMBERSHIP' THEN RETURN ARRAY['MEMBERSHIP:' || account_value || '/' || tenant_value]; END IF;
        RETURN ARRAY['ACCOUNT:' || account_value, 'GLOBAL_ROLES:' || account_value];
    WHEN 'account_authority_issuance_fences' THEN
        RETURN ARRAY['ACCOUNT:' || account_value, 'GLOBAL_ROLES:' || account_value];
    WHEN 'account_membership_pair_authority' THEN
        RETURN ARRAY['MEMBERSHIP:' || account_value || '/' || tenant_value];
    WHEN 'account_tenant_membership' THEN
        SELECT account_uuid::TEXT INTO account_value FROM accounts WHERE id = (row_value->>'account_id')::BIGINT;
        IF tenant_value IS NULL THEN RETURN ARRAY[]::TEXT[]; END IF;
        RETURN ARRAY['MEMBERSHIP:' || account_value || '/' || tenant_value];
    WHEN 'account_tenant_membership_role_snapshots', 'account_tenant_membership_role_snapshot_roles' THEN
        SELECT a.account_uuid::TEXT AS account_uuid, m.tenant_uuid::TEXT AS tenant_uuid INTO member
            FROM account_tenant_membership m JOIN accounts a ON a.id = m.account_id
            WHERE m.id = (row_value->>'membership_id')::BIGINT;
        IF member.tenant_uuid IS NULL THEN RETURN ARRAY[]::TEXT[]; END IF;
        RETURN ARRAY['MEMBERSHIP:' || member.account_uuid || '/' || member.tenant_uuid];
    WHEN 'account_individual_creator_party_sources' THEN
        RETURN ARRAY['CREATOR_PARTY:' || (row_value->>'creator_party_id')];
    WHEN 'account_tenant_creator_party_history' THEN
        RETURN ARRAY['TENANT:' || tenant_value];
    WHEN 'account_hosted_terms_scopes' THEN
        RETURN ARRAY['HOSTED_TERMS:' || (row_value->>'hosted_scope_id')];
    WHEN 'account_individual_hosted_terms_acceptances' THEN
        -- Acceptance currentness is party-specific; another party in this catalog is unrelated.
        RETURN ARRAY['CREATOR_PARTY:' || (row_value->>'creator_party_id')];
    WHEN 'account_hosted_terms_environment_binding_heads' THEN
        RETURN ARRAY['HOSTED_TERMS:environment-boundary/' || (row_value->>'environment_boundary')];
    ELSE
        RAISE EXCEPTION 'Unsupported creator source guard owner' USING ERRCODE = '23514';
    END CASE;
END;
$$;

CREATE FUNCTION account_control_ui_hold_required_sources(source_keys TEXT[])
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    source_key_value TEXT;
    operation_value UUID;
BEGIN
    FOR source_key_value IN SELECT value FROM
        (SELECT DISTINCT value FROM unnest(source_keys) value WHERE value IS NOT NULL) exact_keys
        ORDER BY account_publication_authorization_source_sort_key(value) LOOP
        INSERT INTO account_draft_authorization_source_locks(source_key)
            VALUES (source_key_value) ON CONFLICT DO NOTHING;
        PERFORM source_key FROM account_draft_authorization_source_locks
            WHERE source_key = source_key_value FOR UPDATE NOWAIT;
    END LOOP;
    FOR operation_value IN SELECT DISTINCT s.operation_id
        FROM account_draft_authorization_sources s WHERE s.source_key = ANY(source_keys)
        ORDER BY s.operation_id LOOP
        PERFORM operation_id FROM account_draft_authorization_fences
            WHERE operation_id = operation_value FOR UPDATE NOWAIT;
        IF NOT account_draft_authorization_is_settled(operation_value) THEN
            RAISE EXCEPTION 'Required creator source is held until every original owner settles'
                USING ERRCODE = '55P03';
        END IF;
    END LOOP;
END;
$$;

CREATE FUNCTION account_control_ui_required_source_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    old_value JSONB;
    new_value JSONB;
    protected_columns TEXT[];
    source_keys TEXT[] := ARRAY[]::TEXT[];
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        EXECUTE format('SELECT array_agg(k) FROM %I t CROSS JOIN LATERAL '
            || 'unnest(account_control_ui_source_keys(%L, to_jsonb(t))) k', TG_TABLE_NAME, TG_TABLE_NAME)
            INTO source_keys;
        PERFORM account_control_ui_hold_required_sources(COALESCE(source_keys, ARRAY[]::TEXT[]));
        RETURN NULL;
    END IF;
    IF TG_OP <> 'INSERT' THEN old_value := to_jsonb(OLD); END IF;
    IF TG_OP <> 'DELETE' THEN new_value := to_jsonb(NEW); END IF;
    IF TG_TABLE_NAME = 'accounts' AND TG_OP = 'UPDATE' THEN
        protected_columns := ARRAY['account_uuid','account_uuid_provenance','account_uuid_source_numeric_id',
            'password_hash','email_verified','login_auth_modes','role','lifecycle_state'];
        IF NOT EXISTS (SELECT 1 FROM unnest(protected_columns) c
            WHERE old_value->c IS DISTINCT FROM new_value->c) THEN RETURN NEW; END IF;
    ELSIF TG_OP = 'UPDATE' AND (old_value - ARRAY['created_at','updated_at','recorded_at'])
        IS NOT DISTINCT FROM (new_value - ARRAY['created_at','updated_at','recorded_at']) THEN
        RETURN NEW;
    END IF;
    IF old_value IS NOT NULL THEN
        source_keys := source_keys || account_control_ui_source_keys(TG_TABLE_NAME, old_value);
    END IF;
    IF new_value IS NOT NULL THEN
        source_keys := source_keys || account_control_ui_source_keys(TG_TABLE_NAME, new_value);
    END IF;
    PERFORM account_control_ui_hold_required_sources(source_keys);
    IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END;
$$;

-- Backfill serialization rows from actual owner scopes only; no evidence or grant is backfilled.
DO $$
DECLARE
    table_value TEXT;
    row_value JSONB;
    source_key_value TEXT;
BEGIN
    FOREACH table_value IN ARRAY ARRAY['accounts','account_authority_generations',
        'account_authority_issuance_fences','account_membership_pair_authority',
        'account_tenant_membership','account_tenant_membership_role_snapshots',
        'account_tenant_membership_role_snapshot_roles','account_individual_creator_party_sources',
        'account_tenant_creator_party_history','account_hosted_terms_scopes',
        'account_individual_hosted_terms_acceptances','account_hosted_terms_environment_binding_heads'] LOOP
        FOR row_value IN EXECUTE format('SELECT to_jsonb(t) FROM %I t', table_value) LOOP
            FOREACH source_key_value IN ARRAY account_control_ui_source_keys(table_value, row_value) LOOP
                IF source_key_value IS NOT NULL THEN
                    INSERT INTO account_draft_authorization_source_locks(source_key)
                        VALUES(source_key_value) ON CONFLICT DO NOTHING;
                END IF;
            END LOOP;
        END LOOP;
        EXECUTE format('CREATE TRIGGER account_control_ui_required_source_guard '
            || 'BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW '
            || 'EXECUTE FUNCTION account_control_ui_required_source_guard()', table_value);
        EXECUTE format('CREATE TRIGGER account_control_ui_required_source_truncate_guard '
            || 'BEFORE TRUNCATE ON %I FOR EACH STATEMENT '
            || 'EXECUTE FUNCTION account_control_ui_required_source_guard()', table_value);
    END LOOP;
    FOREACH table_value IN ARRAY ARRAY['account_draft_authorization_source_locks',
        'account_draft_authorization_fences','account_draft_authorization_sources',
        'account_draft_authorization_owner_readbacks','account_draft_authorization_source_changes',
        'account_draft_authorization_changed_scopes'] LOOP
        EXECUTE format('CREATE TRIGGER account_control_ui_draft_no_truncate '
            || 'BEFORE TRUNCATE ON %I FOR EACH STATEMENT '
            || 'EXECUTE FUNCTION account_draft_authorization_immutable_guard()', table_value);
    END LOOP;
END;
$$;
-- [jooq ignore stop]
