-- Forward-only source serialization guards for the existing Account Control UI schema.
-- These guards derive exact owner source keys and do not create or backfill authority.
-- [jooq ignore start]
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
