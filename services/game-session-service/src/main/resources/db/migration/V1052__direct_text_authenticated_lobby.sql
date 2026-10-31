-- Unregistered, non-admitting SQL companion. The minimal original LOGIN journal is unchanged.
-- No credential, compact token, cleanup horizon or release authority belongs to these relations.
CREATE TABLE game_session_direct_text_authenticated_lobby_scope (
    scope_record_id UUID PRIMARY KEY,
    scope_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    original_request_id UUID NOT NULL REFERENCES game_session_initial_gameplay_login_journal(request_id),
    original_caller_context_id UUID NOT NULL,
    original_attempt_fence BIGINT NOT NULL CHECK (original_attempt_fence > 0),
    account_id UUID NOT NULL CHECK (account_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    proxy_connection_id UUID NOT NULL,
    transport_session_id VARCHAR(32) NOT NULL CHECK (transport_session_id ~ '^[0-9]{1,32}$'),
    verified_token_jti UUID NOT NULL,
    verified_token_sha256 VARCHAR(64) NOT NULL CHECK (verified_token_sha256 ~ '^[0-9a-f]{64}$'),
    verified_token_generation BIGINT NOT NULL CHECK (verified_token_generation = 1),
    verified_issuance_fence BIGINT NOT NULL CHECK (verified_issuance_fence > 0),
    scope_bytes BYTEA NOT NULL CHECK (octet_length(scope_bytes) BETWEEN 1 AND 131072),
    UNIQUE (original_request_id, scope_record_id, scope_sequence),
    CHECK (scope_record_id::TEXT ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND proxy_connection_id::TEXT ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND scope_record_id NOT IN (original_request_id, original_caller_context_id, proxy_connection_id, verified_token_jti))
);

CREATE TABLE game_session_direct_text_authenticated_lobby_current (
    original_request_id UUID PRIMARY KEY,
    scope_record_id UUID NOT NULL UNIQUE,
    scope_sequence BIGINT NOT NULL CHECK (scope_sequence > 0),
    FOREIGN KEY (original_request_id, scope_record_id, scope_sequence)
        REFERENCES game_session_direct_text_authenticated_lobby_scope
            (original_request_id, scope_record_id, scope_sequence)
);

CREATE TABLE game_session_direct_text_authenticated_lobby_join (
    scope_record_id UUID PRIMARY KEY REFERENCES game_session_direct_text_authenticated_lobby_scope(scope_record_id),
    join_request_id UUID NOT NULL UNIQUE,
    request_bytes BYTEA NOT NULL CHECK (octet_length(request_bytes) BETWEEN 1 AND 8192),
    state VARCHAR(32) NOT NULL CHECK (state IN ('NEVER_DISPATCHED', 'DISPATCH_POSSIBLE', 'COMPLETED')),
    reply_bytes BYTEA,
    CHECK (join_request_id::TEXT ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CHECK ((state = 'COMPLETED' AND reply_bytes IS NOT NULL
                AND octet_length(reply_bytes) BETWEEN 1 AND 1024)
        OR (state <> 'COMPLETED' AND reply_bytes IS NULL))
);

-- [jooq ignore start]
-- Every new claim takes the ACTUAL original row lock, including direct SQL writers. These
-- comparisons protect storage only: current authenticated Account/custody evidence is mandatory
-- at the manually composed Java owner and is not manufactured by this function.
CREATE FUNCTION require_gs_direct_text_lobby_original(s game_session_direct_text_authenticated_lobby_scope, allow_abandoned BOOLEAN)
RETURNS VOID AS $$
DECLARE
    original game_session_initial_gameplay_login_journal%ROWTYPE;
BEGIN
    SELECT * INTO original FROM game_session_initial_gameplay_login_journal
        WHERE request_id = s.original_request_id FOR UPDATE;
    IF NOT FOUND OR (original.abandoned AND NOT allow_abandoned)
       OR original.verified_token_jti IS NULL
       OR ROW(original.caller_context_id, original.expected_attempt_fence,
              original.verified_token_jti, original.verified_token_sha256,
              original.verified_token_generation, original.verified_issuance_fence)
          IS DISTINCT FROM ROW(s.original_caller_context_id, s.original_attempt_fence,
              s.verified_token_jti, s.verified_token_sha256,
              s.verified_token_generation, s.verified_issuance_fence) THEN
        RAISE EXCEPTION 'Direct-text lobby original is unavailable' USING ERRCODE = '23514';
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION guard_gs_direct_text_lobby_scope() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Direct-text lobby scope retention is held' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW IS DISTINCT FROM OLD THEN
            RAISE EXCEPTION 'Direct-text lobby scope is immutable' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    PERFORM require_gs_direct_text_lobby_original(NEW, FALSE);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER gs_direct_text_lobby_scope_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_direct_text_authenticated_lobby_scope
    FOR EACH ROW EXECUTE FUNCTION guard_gs_direct_text_lobby_scope();
CREATE TRIGGER gs_direct_text_lobby_scope_retention
    BEFORE TRUNCATE ON game_session_direct_text_authenticated_lobby_scope
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_direct_text_lobby_scope();

CREATE FUNCTION guard_gs_direct_text_lobby_current() RETURNS TRIGGER AS $$
DECLARE
    s game_session_direct_text_authenticated_lobby_scope%ROWTYPE;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Direct-text lobby selection retention is held' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO s FROM game_session_direct_text_authenticated_lobby_scope
        WHERE scope_record_id = NEW.scope_record_id;
    IF NOT FOUND OR s.original_request_id <> NEW.original_request_id
        OR s.scope_sequence <> NEW.scope_sequence THEN
        RAISE EXCEPTION 'Direct-text lobby selection differs from retained scope' USING ERRCODE = '23514';
    END IF;
    PERFORM require_gs_direct_text_lobby_original(s, FALSE);
    IF TG_OP = 'UPDATE' AND (NEW.original_request_id <> OLD.original_request_id
        OR NEW.scope_sequence <= OLD.scope_sequence) THEN
        RAISE EXCEPTION 'Direct-text lobby selection cannot regress or rebind' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER gs_direct_text_lobby_current_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_direct_text_authenticated_lobby_current
    FOR EACH ROW EXECUTE FUNCTION guard_gs_direct_text_lobby_current();
CREATE TRIGGER gs_direct_text_lobby_current_retention
    BEFORE TRUNCATE ON game_session_direct_text_authenticated_lobby_current
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_direct_text_lobby_current();

CREATE FUNCTION guard_gs_direct_text_lobby_join() RETURNS TRIGGER AS $$
DECLARE
    s game_session_direct_text_authenticated_lobby_scope%ROWTYPE;
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'Direct-text lobby JOIN retention is held' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO s FROM game_session_direct_text_authenticated_lobby_scope
        WHERE scope_record_id = NEW.scope_record_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Direct-text lobby JOIN scope is missing' USING ERRCODE = '23514';
    END IF;
    IF NEW.join_request_id IN (s.scope_record_id, s.original_request_id,
            s.original_caller_context_id, s.proxy_connection_id, s.verified_token_jti) THEN
        RAISE EXCEPTION 'Direct-text lobby JOIN requires its own identity' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF ROW(NEW.scope_record_id, NEW.join_request_id, NEW.request_bytes)
            IS DISTINCT FROM ROW(OLD.scope_record_id, OLD.join_request_id, OLD.request_bytes) THEN
            RAISE EXCEPTION 'Direct-text lobby JOIN identity is immutable' USING ERRCODE = '23514';
        END IF;
        -- A delayed exact positive result may be retained even after abandonment or replacement.
        -- It never moves the current selection or applies membership/authentication to a transport.
        IF OLD.state = 'DISPATCH_POSSIBLE' AND NEW.state = 'COMPLETED'
            AND NEW.reply_bytes IS NOT NULL THEN
            PERFORM require_gs_direct_text_lobby_original(s, TRUE);
            RETURN NEW;
        END IF;
        IF NOT (OLD.state = 'NEVER_DISPATCHED' AND NEW.state = 'DISPATCH_POSSIBLE'
                AND NEW.reply_bytes IS NULL) THEN
            RAISE EXCEPTION 'Direct-text lobby JOIN cannot reset or overwrite an original' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.state <> 'NEVER_DISPATCHED' OR NEW.reply_bytes IS NOT NULL THEN
        RAISE EXCEPTION 'Direct-text lobby JOIN must begin never-dispatched' USING ERRCODE = '23514';
    END IF;
    PERFORM require_gs_direct_text_lobby_original(s, FALSE);
    IF NOT EXISTS (SELECT 1 FROM game_session_direct_text_authenticated_lobby_current
        WHERE original_request_id = s.original_request_id AND scope_record_id = s.scope_record_id) THEN
        RAISE EXCEPTION 'Direct-text lobby JOIN selection is no longer current' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER gs_direct_text_lobby_join_guard
    BEFORE INSERT OR UPDATE OR DELETE ON game_session_direct_text_authenticated_lobby_join
    FOR EACH ROW EXECUTE FUNCTION guard_gs_direct_text_lobby_join();
CREATE TRIGGER gs_direct_text_lobby_join_retention
    BEFORE TRUNCATE ON game_session_direct_text_authenticated_lobby_join
    FOR EACH STATEMENT EXECUTE FUNCTION guard_gs_direct_text_lobby_join();
-- [jooq ignore stop]
