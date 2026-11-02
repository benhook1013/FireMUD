-- Internal workflow selection only. This head is not a player catalog or an admission pointer.
CREATE TABLE game_session_private_playtest_selections (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_preimage BYTEA NOT NULL CHECK (octet_length(request_preimage) BETWEEN 1 AND 4096),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest = 'sha256:' || encode(sha256(request_preimage), 'hex')),
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    canonical_tenant_id UUID NOT NULL CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    world_slug VARCHAR(120) NOT NULL CHECK (world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    realm_slug VARCHAR(120) NOT NULL CHECK (realm_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    preparation_request_id UUID NOT NULL REFERENCES game_session_initial_playtest_preparations(request_id) ON DELETE RESTRICT,
    playtest_lifecycle_id UUID NOT NULL REFERENCES game_session_initial_playtest_preparations(playtest_lifecycle_id) ON DELETE RESTRICT,
    expected_head_revision BIGINT CHECK (expected_head_revision > 0),
    expected_current_lifecycle_id UUID CHECK (expected_current_lifecycle_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    head_revision BIGINT NOT NULL CHECK (head_revision > 0),
    selected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((expected_head_revision IS NULL AND expected_current_lifecycle_id IS NULL AND head_revision = 1)
        OR (expected_head_revision IS NOT NULL AND expected_current_lifecycle_id IS NOT NULL
            AND head_revision = expected_head_revision + 1)),
    UNIQUE (target_namespace, canonical_tenant_id, world_slug, realm_slug, head_revision),
    UNIQUE (target_namespace, canonical_tenant_id, world_slug, realm_slug, head_revision, request_id, playtest_lifecycle_id)
);

CREATE TABLE game_session_current_private_playtests (
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    realm_slug VARCHAR(120) NOT NULL,
    head_revision BIGINT NOT NULL CHECK (head_revision > 0),
    selection_request_id UUID NOT NULL REFERENCES game_session_private_playtest_selections(request_id) ON DELETE RESTRICT,
    playtest_lifecycle_id UUID NOT NULL,
    PRIMARY KEY (target_namespace, canonical_tenant_id, world_slug, realm_slug),
    FOREIGN KEY (target_namespace, canonical_tenant_id, world_slug, realm_slug, head_revision, selection_request_id, playtest_lifecycle_id)
        REFERENCES game_session_private_playtest_selections
            (target_namespace, canonical_tenant_id, world_slug, realm_slug, head_revision, request_id, playtest_lifecycle_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED
);

-- [jooq ignore start]
CREATE FUNCTION game_session_private_playtest_no_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Current private playtest selection identity and history are retained' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER game_session_private_playtest_selection_immutable BEFORE UPDATE OR DELETE
    ON game_session_private_playtest_selections FOR EACH ROW EXECUTE FUNCTION game_session_private_playtest_no_mutation();
CREATE TRIGGER game_session_private_playtest_selection_no_truncate BEFORE TRUNCATE
    ON game_session_private_playtest_selections FOR EACH STATEMENT EXECUTE FUNCTION game_session_private_playtest_no_mutation();
CREATE TRIGGER game_session_private_playtest_head_no_delete BEFORE DELETE
    ON game_session_current_private_playtests FOR EACH ROW EXECUTE FUNCTION game_session_private_playtest_no_mutation();
CREATE TRIGGER game_session_private_playtest_head_no_truncate BEFORE TRUNCATE
    ON game_session_current_private_playtests FOR EACH STATEMENT EXECUTE FUNCTION game_session_private_playtest_no_mutation();

CREATE FUNCTION game_session_private_playtest_head_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.head_revision <> 1)
        OR (TG_OP = 'UPDATE' AND (NEW.target_namespace IS DISTINCT FROM OLD.target_namespace
            OR NEW.canonical_tenant_id IS DISTINCT FROM OLD.canonical_tenant_id
            OR NEW.world_slug IS DISTINCT FROM OLD.world_slug OR NEW.realm_slug IS DISTINCT FROM OLD.realm_slug
            OR NEW.head_revision <> OLD.head_revision + 1)) THEN
        RAISE EXCEPTION 'Current private playtest head must advance exactly once in its immutable scope' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_private_playtest_head_guard BEFORE INSERT OR UPDATE
    ON game_session_current_private_playtests FOR EACH ROW EXECUTE FUNCTION game_session_private_playtest_head_guard();

CREATE FUNCTION game_session_private_playtest_selection_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    head game_session_current_private_playtests%ROWTYPE;
    preparation game_session_initial_playtest_preparations%ROWTYPE;
    fields TEXT[];
    item TEXT;
    framed BYTEA := ''::BYTEA;
BEGIN
    fields := ARRAY['game-session-current-private-playtest-selection/v1', NEW.request_id::TEXT,
        NEW.target_namespace, NEW.canonical_tenant_id::TEXT, NEW.world_slug, NEW.realm_slug,
        NEW.preparation_request_id::TEXT, NEW.playtest_lifecycle_id::TEXT,
        CASE WHEN NEW.expected_head_revision IS NULL THEN 'ABSENT' ELSE 'PRESENT' END];
    IF NEW.expected_head_revision IS NOT NULL THEN
        fields := fields || ARRAY[NEW.expected_head_revision::TEXT, NEW.expected_current_lifecycle_id::TEXT];
    END IF;
    FOREACH item IN ARRAY fields LOOP
        framed := framed || int4send(octet_length(convert_to(item, 'UTF8'))) || convert_to(item, 'UTF8');
    END LOOP;
    IF framed <> NEW.request_preimage THEN
        RAISE EXCEPTION 'Private playtest selection bytes differ from the exact workflow request' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO head FROM game_session_current_private_playtests
        WHERE target_namespace = NEW.target_namespace AND canonical_tenant_id = NEW.canonical_tenant_id
            AND world_slug = NEW.world_slug AND realm_slug = NEW.realm_slug FOR UPDATE;
    IF (head.head_revision IS NULL AND NEW.expected_head_revision IS NOT NULL)
        OR (head.head_revision IS NOT NULL AND (NEW.expected_head_revision IS DISTINCT FROM head.head_revision
            OR NEW.expected_current_lifecycle_id IS DISTINCT FROM head.playtest_lifecycle_id)) THEN
        RAISE EXCEPTION 'Private playtest selection expected head differs from current lifecycle' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT preparation FROM game_session_initial_playtest_preparations
        WHERE request_id = NEW.preparation_request_id AND playtest_lifecycle_id = NEW.playtest_lifecycle_id FOR SHARE;
    IF preparation.target_namespace <> NEW.target_namespace OR preparation.canonical_tenant_id <> NEW.canonical_tenant_id
        OR preparation.world_slug <> NEW.world_slug OR preparation.realm_slug <> NEW.realm_slug
        OR preparation.initialization_mode <> 'FRESH' OR preparation.preparation_state <> 'PREPARING'
        OR preparation.playtest_state_generation <> 1 OR NOT preparation.target_policy_visible
        OR preparation.target_policy_public_production OR preparation.target_policy_state_scope <> 'ISOLATED'
        OR preparation.target_policy_world_slug <> NEW.world_slug OR preparation.target_policy_realm_slug <> NEW.realm_slug
        OR preparation.fork_expiry_second + preparation.fork_expiry_nano / 1000000000.0 <= extract(epoch FROM clock_timestamp()) THEN
        RAISE EXCEPTION 'Current private playtest selection requires exact unexpired qualified FRESH private preparation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_private_playtest_selection_guard BEFORE INSERT
    ON game_session_private_playtest_selections FOR EACH ROW EXECUTE FUNCTION game_session_private_playtest_selection_guard();

CREATE FUNCTION game_session_private_playtest_selection_commit_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    preparation game_session_initial_playtest_preparations%ROWTYPE;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM game_session_current_private_playtests head
        WHERE head.target_namespace = NEW.target_namespace AND head.canonical_tenant_id = NEW.canonical_tenant_id
            AND head.world_slug = NEW.world_slug AND head.realm_slug = NEW.realm_slug
            AND head.head_revision >= NEW.head_revision) THEN
        RAISE EXCEPTION 'Private playtest selection receipt and current head must commit together' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT preparation FROM game_session_initial_playtest_preparations
        WHERE request_id = NEW.preparation_request_id AND playtest_lifecycle_id = NEW.playtest_lifecycle_id;
    IF preparation.fork_expiry_second + preparation.fork_expiry_nano / 1000000000.0 <= extract(epoch FROM clock_timestamp()) THEN
        RAISE EXCEPTION 'Selected private playtest expired before final commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER game_session_private_playtest_selection_commit_guard AFTER INSERT
    ON game_session_private_playtest_selections DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION game_session_private_playtest_selection_commit_guard();
-- [jooq ignore stop]
