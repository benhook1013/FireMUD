-- Bound possible Account scope issuances before dispatch. Historical outbound attempts cannot
-- be reconstructed from retained scopes, so existing LOGIN originals receive no fresh intake.
ALTER TABLE game_session_initial_gameplay_login_journal
    ADD COLUMN lobby_scope_intake_eligible BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE game_session_initial_gameplay_login_journal
    ALTER COLUMN lobby_scope_intake_eligible SET DEFAULT TRUE;

CREATE TABLE game_session_direct_text_authenticated_lobby_scope_intake (
    scope_record_id UUID PRIMARY KEY,
    scope_request_id UUID NOT NULL UNIQUE,
    original_request_id UUID NOT NULL REFERENCES game_session_initial_gameplay_login_journal(request_id),
    frozen_limit INTEGER NOT NULL CHECK (frozen_limit > 0),
    intake_sequence INTEGER NOT NULL CHECK (intake_sequence > 0 AND intake_sequence <= frozen_limit),
    UNIQUE (original_request_id, intake_sequence),
    CHECK (scope_record_id::TEXT ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND scope_request_id::TEXT ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND scope_record_id NOT IN (original_request_id, scope_request_id)
        AND scope_request_id <> original_request_id)
);

-- [jooq ignore start]
CREATE FUNCTION guard_gs_lobby_intake_eligibility() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.lobby_scope_intake_eligible IS DISTINCT FROM OLD.lobby_scope_intake_eligible THEN
        RAISE EXCEPTION 'LOGIN scope-intake eligibility cannot be rewritten' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER gs_login_lobby_intake_eligibility
    BEFORE UPDATE ON game_session_initial_gameplay_login_journal
    FOR EACH ROW EXECUTE FUNCTION guard_gs_lobby_intake_eligibility();

CREATE FUNCTION guard_gs_direct_text_lobby_scope_intake() RETURNS TRIGGER AS $$
DECLARE
    original game_session_initial_gameplay_login_journal%ROWTYPE;
    prior game_session_direct_text_authenticated_lobby_scope_intake%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Possible lobby scope issuances remain immutable and retained' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO original FROM game_session_initial_gameplay_login_journal
        WHERE request_id = NEW.original_request_id FOR UPDATE;
    IF NOT FOUND OR original.abandoned OR NOT original.lobby_scope_intake_eligible
        OR original.verified_token_jti IS NULL THEN
        RAISE EXCEPTION 'Fresh LOGIN scope-intake original is unavailable' USING ERRCODE = '23514';
    END IF;
    IF NEW.scope_record_id IN (original.caller_context_id, original.verified_token_jti)
        OR NEW.scope_request_id IN (original.caller_context_id, original.verified_token_jti) THEN
        RAISE EXCEPTION 'Scope intake requires independent identities' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO prior FROM game_session_direct_text_authenticated_lobby_scope_intake
        WHERE original_request_id = NEW.original_request_id ORDER BY intake_sequence DESC LIMIT 1;
    IF FOUND THEN
        IF NEW.frozen_limit <> prior.frozen_limit OR prior.intake_sequence >= prior.frozen_limit
            OR NEW.intake_sequence <> prior.intake_sequence + 1 THEN
            RAISE EXCEPTION 'LOGIN scope-intake budget cannot change or exceed its bound' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.intake_sequence <> 1 THEN
        RAISE EXCEPTION 'LOGIN scope-intake sequence must begin at one' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER gs_direct_text_lobby_scope_intake_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_direct_text_authenticated_lobby_scope_intake
    FOR EACH ROW EXECUTE FUNCTION guard_gs_direct_text_lobby_scope_intake();
CREATE TRIGGER gs_direct_text_lobby_scope_intake_retention
    BEFORE TRUNCATE ON game_session_direct_text_authenticated_lobby_scope_intake
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_direct_text_lobby_scope_intake();

CREATE FUNCTION require_gs_direct_text_lobby_scope_intake() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM game_session_direct_text_authenticated_lobby_scope_intake
        WHERE scope_record_id = NEW.scope_record_id AND original_request_id = NEW.original_request_id) THEN
        RAISE EXCEPTION 'Lobby scope requires its retained pre-dispatch intake claim' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER gs_direct_text_lobby_scope_intake_required
    BEFORE INSERT ON game_session_direct_text_authenticated_lobby_scope
    FOR EACH ROW EXECUTE FUNCTION require_gs_direct_text_lobby_scope_intake();

INSERT INTO game_session_runtime_family_classification
    (family_id,relation_name,family_role,state_class,fresh_disposition,retention_disposition,owner_consequence)
VALUES ('game_session_direct_text_authenticated_lobby_scope_intake',
    'game_session_direct_text_authenticated_lobby_scope_intake','CONTROL','NOT_PAYLOAD',
    'CONTROL_ONLY','OWNER_POLICY_RETAINED',
    'Original LOGIN possible scope-issuance claims freeze a finite intake bound; unknown replies never refund claims. No credential, admission, cleanup or live-custody authority.');

-- Extend the exact installed frontier and stable marker without changing historical artifacts.
DO $migration$
DECLARE
    definition text;
    original text;
    replacement text;
BEGIN
    SELECT pg_get_functiondef((quote_ident(current_schema()) ||
        '.gs_runtime_family_catalog_installed()')::regprocedure) INTO STRICT definition;
    original := $needle$FROM "${flyway:table}" WHERE version='1064'$needle$;
    replacement := original || E'\n    ) AND (\n        SELECT count(*)=1 AND coalesce(bool_and(type=''SQL''\n            AND script=''V1065__direct_text_lobby_scope_intake_bound.sql'' AND success),false)\n        FROM "${flyway:table}" WHERE version=''1065''';
    IF (length(definition) - length(replace(definition, original, ''))) / length(original) <> 1 THEN
        RAISE EXCEPTION 'Expected exact Game Session installed frontier';
    END IF;
    EXECUTE replace(definition, original, replacement);

    SELECT pg_get_functiondef((quote_ident(current_schema()) ||
        '.gs_runtime_family_catalog_bytes(bigint)')::regprocedure) INTO STRICT definition;
    original := $needle$AND version IS DISTINCT FROM '1064'$needle$;
    replacement := original || E'\n            AND version IS DISTINCT FROM ''1065''';
    IF (length(definition) - length(replace(definition, original, ''))) / length(original) <> 1 THEN
        RAISE EXCEPTION 'Expected exact Game Session catalog history exclusion';
    END IF;
    definition := replace(definition, original, replacement);
    original := $needle$SELECT 'MIGRATION','catalog-installation:1064','V1064__restore_final_human_completion_lower_bound.sql'$needle$;
    replacement := original || E'\n        UNION ALL\n        SELECT ''MIGRATION'',''catalog-installation:1065'',''V1065__direct_text_lobby_scope_intake_bound.sql''';
    IF (length(definition) - length(replace(definition, original, ''))) / length(original) <> 1 THEN
        RAISE EXCEPTION 'Expected exact Game Session catalog installation marker';
    END IF;
    EXECUTE replace(definition, original, replacement);
END;
$migration$;

INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT epoch,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (
    SELECT epoch,gs_runtime_family_catalog_bytes(epoch) AS bytes
    FROM (SELECT coalesce(max(catalog_epoch),0)+1 AS epoch
        FROM game_session_runtime_family_catalog_epoch) next_epoch
) seal;
-- [jooq ignore stop]
