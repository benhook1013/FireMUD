-- Initial FRESH preparation allocation only. This creates neither catalog visibility nor an
-- admission pointer. All retained runtime, catalog and namespace rows remain unchanged.
CREATE TABLE game_session_initial_playtest_preparations (
    request_id UUID PRIMARY KEY CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_preimage BYTEA NOT NULL CHECK (octet_length(request_preimage) BETWEEN 1 AND 16384),
    request_digest VARCHAR(71) NOT NULL CHECK (request_digest = 'sha256:' || encode(sha256(request_preimage), 'hex')),
    target_namespace VARCHAR(63) NOT NULL CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    canonical_tenant_id UUID NOT NULL CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    world_slug VARCHAR(120) NOT NULL CHECK (world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    realm_slug VARCHAR(120) NOT NULL CHECK (realm_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    initialization_mode VARCHAR(8) NOT NULL CHECK (initialization_mode = 'FRESH'),
    preparation_state VARCHAR(16) NOT NULL CHECK (preparation_state = 'PREPARING'),
    playtest_lifecycle_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE
        CHECK (playtest_lifecycle_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    playable_state_namespace_id UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE
        CHECK (playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    playtest_state_generation BIGINT NOT NULL CHECK (playtest_state_generation = 1),
    fork_expiry_second BIGINT NOT NULL CHECK (fork_expiry_second BETWEEN -62135596800 AND 253402300799),
    fork_expiry_nano INTEGER NOT NULL CHECK (fork_expiry_nano BETWEEN 0 AND 999999999),
    fork_expiry_text VARCHAR(30) NOT NULL,
    source_catalog_realm_id UUID NOT NULL,
    source_catalog_request_id UUID NOT NULL,
    source_catalog_revision BIGINT NOT NULL CHECK (source_catalog_revision = 1),
    source_catalog_receipt_digest VARCHAR(71) NOT NULL CHECK (source_catalog_receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_intake_operation_id UUID NOT NULL REFERENCES game_session_authored_world_source_intake(operation_id) ON DELETE RESTRICT,
    source_intake_receipt_digest VARCHAR(71) NOT NULL CHECK (source_intake_receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_operation_id UUID NOT NULL,
    source_evidence_digest VARCHAR(71) NOT NULL CHECK (source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'),
    canonical_version_id UUID NOT NULL CHECK (canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    published_release_bundle_ref VARCHAR(1024) NOT NULL CHECK (length(btrim(published_release_bundle_ref)) BETWEEN 1 AND 1024),
    published_release_bundle_digest VARCHAR(71) NOT NULL CHECK (published_release_bundle_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_policy_set_digest VARCHAR(71) NOT NULL CHECK (source_policy_set_digest ~ '^sha256:[0-9a-f]{64}$'),
    source_policy_carrier_digest VARCHAR(71) NOT NULL CHECK (source_policy_carrier_digest ~ '^sha256:[0-9a-f]{64}$'),
    target_policy_id UUID NOT NULL CHECK (target_policy_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    target_policy_source_commit_id UUID NOT NULL CHECK (target_policy_source_commit_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    target_policy_source_revision_id UUID NOT NULL CHECK (target_policy_source_revision_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    target_policy_logical_revision_id VARCHAR(128) NOT NULL CHECK (char_length(target_policy_logical_revision_id) BETWEEN 1 AND 128 AND target_policy_logical_revision_id !~ '^[[:space:]]*$'),
    target_policy_digest VARCHAR(71) NOT NULL CHECK (target_policy_digest ~ '^sha256:[0-9a-f]{64}$'),
    target_policy_canonical_json BYTEA NOT NULL CHECK (octet_length(target_policy_canonical_json) BETWEEN 1 AND 4096),
    target_policy_schema_version INTEGER NOT NULL CHECK (target_policy_schema_version IN (1, 2, 3)),
    target_policy_world_slug VARCHAR(120) NOT NULL CHECK (target_policy_world_slug = world_slug),
    target_policy_realm_slug VARCHAR(120) NOT NULL CHECK (target_policy_realm_slug = realm_slug),
    target_policy_visible BOOLEAN NOT NULL CHECK (target_policy_visible),
    target_policy_world_display_name VARCHAR(200) NOT NULL CHECK (char_length(target_policy_world_display_name) BETWEEN 1 AND 100 AND octet_length(target_policy_world_display_name) <= 400 AND target_policy_world_display_name !~ '^[[:space:]]*$'),
    target_policy_public_production BOOLEAN NOT NULL CHECK (NOT target_policy_public_production),
    target_policy_state_scope VARCHAR(16) NOT NULL CHECK (target_policy_state_scope = 'ISOLATED'),
    target_policy_realm_display_name VARCHAR(128) NOT NULL CHECK (char_length(target_policy_realm_display_name) BETWEEN 1 AND 128 AND octet_length(target_policy_realm_display_name) <= 512 AND target_policy_realm_display_name !~ '^[[:space:]]*$'),
    target_policy_entry_policy VARCHAR(32) NOT NULL CHECK (target_policy_entry_policy IN ('PRESEEDED_ONLY', 'PLAYER_CREATED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (playtest_lifecycle_id <> playable_state_namespace_id),
    FOREIGN KEY (target_namespace, source_catalog_realm_id)
        REFERENCES game_session_canonical_realm_catalog(target_namespace, realm_id) ON DELETE RESTRICT,
    FOREIGN KEY (target_namespace, canonical_tenant_id)
        REFERENCES game_session_authored_world_tenant_source_binding(target_namespace, canonical_tenant_id) ON DELETE RESTRICT
);

-- [jooq ignore start]
CREATE FUNCTION game_session_initial_playtest_no_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Initial playtest preparation identity and original evidence are immutable and retained' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER game_session_initial_playtest_immutable BEFORE UPDATE OR DELETE
    ON game_session_initial_playtest_preparations FOR EACH ROW
    EXECUTE FUNCTION game_session_initial_playtest_no_mutation();
CREATE TRIGGER game_session_initial_playtest_no_truncate BEFORE TRUNCATE
    ON game_session_initial_playtest_preparations FOR EACH STATEMENT
    EXECUTE FUNCTION game_session_initial_playtest_no_mutation();

CREATE FUNCTION game_session_initial_playtest_source_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    source game_session_canonical_realm_catalog%ROWTYPE;
    intake game_session_authored_world_source_intake%ROWTYPE;
    carrier BYTEA;
    payload_bytes BYTEA;
    payload TEXT;
    policy JSONB;
    selected_policy_count INTEGER;
    selected_policy_json TEXT;
    selected_policy JSONB;
    marker TEXT := 'game-design-published-realm-policy-carrier/v1';
    fields TEXT[];
    item TEXT;
    framed BYTEA := ''::BYTEA;
    fraction TEXT;
BEGIN
    IF NEW.fork_expiry_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{3}|\.[0-9]{6}|\.[0-9]{9})?Z$'
        OR extract(epoch FROM regexp_replace(NEW.fork_expiry_text, '\.[0-9]+Z$', 'Z')::TIMESTAMPTZ) <> NEW.fork_expiry_second THEN
        RAISE EXCEPTION 'Exact fork expiry text differs from persisted seconds' USING ERRCODE = '23514';
    END IF;
    fraction := substring(NEW.fork_expiry_text FROM '\.([0-9]+)Z$');
    IF coalesce(rpad(fraction, 9, '0')::INTEGER, 0) <> NEW.fork_expiry_nano THEN
        RAISE EXCEPTION 'Exact fork expiry text differs from persisted nanoseconds' USING ERRCODE = '23514';
    END IF;
    fields := ARRAY['game-session-initial-fresh-playtest-request/v1', NEW.request_id::TEXT,
        NEW.target_namespace, NEW.canonical_tenant_id::TEXT, NEW.world_slug, NEW.realm_slug,
        NEW.source_catalog_realm_id::TEXT, NEW.source_catalog_request_id::TEXT, NEW.source_catalog_revision::TEXT,
        NEW.source_intake_operation_id::TEXT, NEW.canonical_version_id::TEXT, NEW.published_release_bundle_ref,
        NEW.published_release_bundle_digest, NEW.initialization_mode, NEW.fork_expiry_text];
    FOREACH item IN ARRAY fields LOOP
        -- int4send is the four-byte network-order length used by the shared Java frame.
        framed := framed || int4send(octet_length(convert_to(item, 'UTF8'))) || convert_to(item, 'UTF8');
    END LOOP;
    IF framed <> NEW.request_preimage THEN
        RAISE EXCEPTION 'Initial playtest request bytes differ from exact stored intent' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT source FROM game_session_canonical_realm_catalog
        WHERE target_namespace = NEW.target_namespace AND realm_id = NEW.source_catalog_realm_id FOR SHARE;
    SELECT * INTO STRICT intake FROM game_session_authored_world_source_intake
        WHERE operation_id = NEW.source_intake_operation_id FOR SHARE;
    IF NOT source.public_production OR NOT source.visible
        OR source.complete_policy_set_evidence IS NULL OR source.fixture_policy_request_bytes IS NOT NULL
        OR source.canonical_tenant_id <> NEW.canonical_tenant_id OR source.world_slug <> NEW.world_slug
        OR source.realm_slug = NEW.realm_slug
        OR source.creation_request_id <> NEW.source_catalog_request_id
        OR source.catalog_revision <> NEW.source_catalog_revision
        OR source.receipt_digest <> NEW.source_catalog_receipt_digest
        OR source.source_intake_operation_id <> NEW.source_intake_operation_id
        OR source.source_intake_receipt_digest <> NEW.source_intake_receipt_digest
        OR source.source_operation_id <> NEW.source_operation_id
        OR source.source_evidence_digest <> NEW.source_evidence_digest
        OR intake.receipt_digest <> NEW.source_intake_receipt_digest
        OR intake.target_namespace <> NEW.target_namespace OR intake.canonical_tenant_id <> NEW.canonical_tenant_id
        OR intake.world_slug <> NEW.world_slug OR intake.source_operation_id <> NEW.source_operation_id
        OR intake.source_evidence_digest <> NEW.source_evidence_digest
        OR NEW.source_policy_carrier_digest <> 'sha256:' || encode(sha256(source.complete_policy_set_evidence), 'hex')
        OR EXISTS (SELECT 1 FROM game_session_canonical_realm_catalog target
            WHERE target.target_namespace = NEW.target_namespace AND target.canonical_tenant_id = NEW.canonical_tenant_id
                AND target.world_slug = NEW.world_slug AND target.realm_slug = NEW.realm_slug)
        OR EXISTS (SELECT 1 FROM gameplay_tenant_shared_playable_state_namespace namespace
            WHERE namespace.playable_state_namespace_id = NEW.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM game_session_canonical_realm_catalog catalog
            WHERE catalog.playable_state_namespace_id = NEW.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_admission_pointer pointer
            WHERE pointer.playable_state_namespace_id = NEW.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_published_tenant_shared_playable_state_namespace namespace
            WHERE namespace.playable_state_namespace_id = NEW.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_published_realm_catalog_entry entry
            WHERE entry.playable_state_namespace_id = NEW.playable_state_namespace_id) THEN
        RAISE EXCEPTION 'Initial playtest requires exact retained canonical public source/build evidence and a fresh private target' USING ERRCODE = '23514';
    END IF;
    -- Read only the exact retained shared carrier; Java additionally verifies its closed
    -- publication operation/capture/terminal contracts before reaching this guarded insert.
    carrier := source.complete_policy_set_evidence;
    IF octet_length(carrier) < 8 + octet_length(convert_to(marker, 'UTF8')) THEN
        RAISE EXCEPTION 'Retained build source carrier framing is invalid' USING ERRCODE = '23514';
    END IF;
    -- The carrier is exactly two shared binary frames: schema marker, then canonical JSON.
    -- Derive the actual second-frame bytes and compare both complete length headers exactly.
    payload_bytes := substring(carrier FROM 9 + octet_length(convert_to(marker, 'UTF8')));
    IF carrier <> (int4send(octet_length(convert_to(marker, 'UTF8'))) || convert_to(marker, 'UTF8')
        || int4send(octet_length(payload_bytes)) || payload_bytes) THEN
        RAISE EXCEPTION 'Retained build source carrier framing is invalid' USING ERRCODE = '23514';
    END IF;
    payload := convert_from(payload_bytes, 'UTF8');
    policy := payload::JSONB;
    IF policy->>'schema' IS DISTINCT FROM marker
        OR policy->'target'->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR policy->'target'->>'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR policy->'target'->>'sourceGameRowId' IS DISTINCT FROM source.source_game_row_id::TEXT
        OR policy->'target'->>'sourceGameTenantKey' IS DISTINCT FROM source.source_game_tenant_key
        OR policy->'target'->>'sourceProvenanceKind' IS DISTINCT FROM source.source_provenance_kind
        OR policy->>'publishedReleaseBundleRef' IS DISTINCT FROM NEW.published_release_bundle_ref
        OR policy->>'publishedReleaseBundleDigest' IS DISTINCT FROM NEW.published_release_bundle_digest
        OR policy->>'policySetDigest' IS DISTINCT FROM NEW.source_policy_set_digest THEN
        RAISE EXCEPTION 'Initial playtest build differs from exact retained published source' USING ERRCODE = '23514';
    END IF;
    SELECT count(*), max(policy_item.value->'source'->>'canonicalPolicyJson')
      INTO selected_policy_count, selected_policy_json
      FROM jsonb_array_elements(policy->'policies') AS policy_item(value)
     WHERE policy_item.value->>'policyId' = NEW.target_policy_id::TEXT
       AND policy_item.value->>'policyDigest' = NEW.target_policy_digest
       AND policy_item.value->'source'->>'commitId' = NEW.target_policy_source_commit_id::TEXT
       AND policy_item.value->'source'->>'revisionId' = NEW.target_policy_source_revision_id::TEXT
       AND policy_item.value->'source'->>'logicalRevisionId' = NEW.target_policy_logical_revision_id;
    IF selected_policy_count <> 1 OR selected_policy_json IS NULL
       OR convert_to(selected_policy_json, 'UTF8') IS DISTINCT FROM NEW.target_policy_canonical_json THEN
        RAISE EXCEPTION 'Selected private target policy differs from the exact retained published policy set' USING ERRCODE = '23514';
    END IF;
    selected_policy := selected_policy_json::JSONB;
    IF selected_policy->>'schemaVersion' IS DISTINCT FROM NEW.target_policy_schema_version::TEXT
       OR selected_policy->>'worldSlug' IS DISTINCT FROM NEW.target_policy_world_slug
       OR selected_policy->>'realmSlug' IS DISTINCT FROM NEW.target_policy_realm_slug
       OR selected_policy->>'visible' IS DISTINCT FROM 'true'
       OR selected_policy->>'worldDisplayName' IS DISTINCT FROM NEW.target_policy_world_display_name
       OR NEW.target_policy_world_display_name IS DISTINCT FROM intake.world_display_name
       OR selected_policy->>'publicProduction' IS DISTINCT FROM 'false'
       OR selected_policy->>'stateScope' IS DISTINCT FROM NEW.target_policy_state_scope
       OR selected_policy->>'realmDisplayName' IS DISTINCT FROM NEW.target_policy_realm_display_name
       OR selected_policy->>'entryPolicy' IS DISTINCT FROM NEW.target_policy_entry_policy THEN
        RAISE EXCEPTION 'Selected private target policy semantics differ from the exact retained policy bytes' USING ERRCODE = '23514';
    END IF;
    IF NEW.fork_expiry_second + NEW.fork_expiry_nano / 1000000000.0 <= extract(epoch FROM clock_timestamp()) THEN
        RAISE EXCEPTION 'Initial playtest fork expiry must be future' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_initial_playtest_source_guard BEFORE INSERT
    ON game_session_initial_playtest_preparations FOR EACH ROW
    EXECUTE FUNCTION game_session_initial_playtest_source_guard();

CREATE FUNCTION game_session_initial_playtest_commit_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.fork_expiry_second + NEW.fork_expiry_nano / 1000000000.0 <= extract(epoch FROM clock_timestamp()) THEN
        RAISE EXCEPTION 'Initial playtest expired before final preparation commit' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER game_session_initial_playtest_commit_guard AFTER INSERT
    ON game_session_initial_playtest_preparations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION game_session_initial_playtest_commit_guard();
-- [jooq ignore stop]
