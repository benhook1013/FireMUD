-- Account-wide negative pending-intent protection only. Unchanged tuple versions fence stale
-- REPEATABLE_READ/SERIALIZABLE snapshots; no logical source, restriction or authority is created.
-- Retained WAITING evidence is preserved, including unmatched or contradictory historical rows.
-- [jooq ignore start]
CREATE FUNCTION account_security_state_account_row_version_fence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    touched_rows INTEGER;
BEGIN
    IF NEW.status = 'WAITING' THEN
        UPDATE accounts SET id = id
            WHERE account_uuid = NEW.account_uuid
              AND id = NEW.account_id
              AND account_uuid_source_numeric_id = NEW.account_id
              AND account_uuid_provenance = NEW.account_provenance;
        GET DIAGNOSTICS touched_rows = ROW_COUNT;
        IF touched_rows <> 1 THEN
            RAISE EXCEPTION 'Security-state intent requires the exact persisted Account row'
                USING ERRCODE = '23514',
                    CONSTRAINT = 'account_security_state_account_row_version_fence';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_security_state_account_row_version_fence
    BEFORE INSERT ON account_security_state_operations
    FOR EACH ROW EXECUTE FUNCTION account_security_state_account_row_version_fence();

CREATE FUNCTION account_draft_authorization_account_row_version_fence()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    account_key TEXT;
    touched_rows INTEGER;
BEGIN
    IF split_part(NEW.source_key, ':', 1) IN ('ACCOUNT', 'GLOBAL_ROLES') THEN
        IF NEW.source_key !~ '^(ACCOUNT|GLOBAL_ROLES):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
            RAISE EXCEPTION 'Account source scope must use the exact canonical UUID key'
                USING ERRCODE = '23514',
                    CONSTRAINT = 'account_draft_authorization_account_row_version_fence';
        END IF;
        account_key := split_part(NEW.source_key, ':', 2);
        UPDATE accounts SET id = id
            WHERE account_uuid = account_key::UUID
              AND account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
              AND id > 0 AND account_uuid_source_numeric_id = id
              AND account_uuid_provenance IN (
                  'ACCOUNT_V29_MIGRATION', 'ACCOUNT_REPOSITORY_INSERT', 'ACCOUNT_DATABASE_INSERT');
        GET DIAGNOSTICS touched_rows = ROW_COUNT;
        IF touched_rows <> 1 THEN
            RAISE EXCEPTION 'Account source scope requires the exact persisted Account row'
                USING ERRCODE = '23514',
                    CONSTRAINT = 'account_draft_authorization_account_row_version_fence';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- Alphabetically earlier than V76's scope_insert_phase: Account is touched before parent locking.
CREATE TRIGGER account_draft_authorization_account_row_version_fence
    BEFORE INSERT ON account_draft_authorization_changed_scopes
    FOR EACH ROW EXECUTE FUNCTION account_draft_authorization_account_row_version_fence();

-- Protect snapshots predating installation without asserting retained intent provenance or
-- enrolling an Account. Exact UUID/key matching supplies negative protection only.
DO $$
DECLARE
    pending_account UUID;
BEGIN
    FOR pending_account IN
        SELECT a.account_uuid FROM accounts a
        WHERE EXISTS (
            SELECT 1 FROM account_security_state_operations security
            WHERE security.account_uuid = a.account_uuid AND security.status = 'WAITING')
        OR EXISTS (
            SELECT 1 FROM account_draft_authorization_source_changes change
            JOIN account_draft_authorization_changed_scopes changed USING (change_id)
            WHERE change.status = 'WAITING'
              AND changed.source_key IN (
                  'ACCOUNT:' || a.account_uuid::TEXT, 'GLOBAL_ROLES:' || a.account_uuid::TEXT))
        ORDER BY a.account_uuid::TEXT
    LOOP
        UPDATE accounts SET id = id WHERE account_uuid = pending_account;
    END LOOP;
END;
$$;
-- [jooq ignore stop]
