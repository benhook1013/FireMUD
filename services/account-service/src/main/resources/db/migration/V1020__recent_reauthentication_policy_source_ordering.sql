-- Make only actual current recent-reauthentication policy pointers participants in the existing
-- Account Control UI source-key serialization. These rows establish no policy value or authority.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_control_ui_source_keys(table_name TEXT, row_value JSONB)
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
    WHEN 'account_recent_reauthentication_policy_current' THEN
        scope_kind := row_value->>'policy_scope';
        IF scope_kind IS NULL OR scope_kind NOT IN ('PLATFORM', 'DEPLOYMENT') THEN
            RAISE EXCEPTION 'Unsupported Account reauthentication policy scope'
                USING ERRCODE = '23514';
        END IF;
        RETURN ARRAY['RECENT_REAUTH_POLICY:SELF_SERVICE_GAME_CREATION/' || scope_kind];
    ELSE
        RAISE EXCEPTION 'Unsupported creator source guard owner' USING ERRCODE = '23514';
    END CASE;
END;
$$;

-- Existing current pointers alone seed their serialization rows. A missing pointer remains
-- missing and unavailable; this migration does not install or infer a default policy value.
DO $$
DECLARE
    row_value JSONB;
    source_key_value TEXT;
BEGIN
    FOR row_value IN SELECT to_jsonb(current_row)
        FROM account_recent_reauthentication_policy_current current_row
    LOOP
        FOREACH source_key_value IN ARRAY
            account_control_ui_source_keys('account_recent_reauthentication_policy_current', row_value)
        LOOP
            INSERT INTO account_draft_authorization_source_locks(source_key)
                VALUES (source_key_value) ON CONFLICT DO NOTHING;
        END LOOP;
    END LOOP;

    EXECUTE 'CREATE TRIGGER account_control_ui_required_source_guard '
        || 'BEFORE INSERT OR UPDATE OR DELETE ON account_recent_reauthentication_policy_current '
        || 'FOR EACH ROW EXECUTE FUNCTION account_control_ui_required_source_guard()';
    EXECUTE 'CREATE TRIGGER account_control_ui_required_source_truncate_guard '
        || 'BEFORE TRUNCATE ON account_recent_reauthentication_policy_current '
        || 'FOR EACH STATEMENT EXECUTE FUNCTION account_control_ui_required_source_guard()';
END;
$$;
-- [jooq ignore stop]
