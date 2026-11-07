-- Private lifecycle evolution appends non-admitting source evidence while the realm UUID,
-- tenant/world/realm selectors and immutable original revision-one allocation stay fixed.
CREATE TABLE game_session_private_catalog_revision_history (
    realm_id UUID NOT NULL REFERENCES game_session_canonical_realm_catalog(realm_id) ON DELETE RESTRICT,
    catalog_revision BIGINT NOT NULL CHECK (catalog_revision >= 2),
    request_id UUID NOT NULL UNIQUE CHECK (request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    request_preimage BYTEA NOT NULL CHECK (octet_length(request_preimage) BETWEEN 1 AND 4096),
    request_digest VARCHAR(71) NOT NULL
        CHECK (request_digest = 'sha256:' || encode(sha256(request_preimage), 'hex')),
    receipt_digest VARCHAR(71) NOT NULL CHECK (receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    preparation_request_id UUID NOT NULL UNIQUE
        REFERENCES game_session_initial_playtest_preparations(request_id) ON DELETE RESTRICT,
    playtest_lifecycle_id UUID NOT NULL
        REFERENCES game_session_initial_playtest_preparations(playtest_lifecycle_id) ON DELETE RESTRICT,
    playable_state_namespace_id UUID NOT NULL UNIQUE
        REFERENCES game_session_initial_playtest_preparations(playable_state_namespace_id) ON DELETE RESTRICT,
    expected_catalog_revision BIGINT NOT NULL CHECK (expected_catalog_revision > 0),
    expected_current_lifecycle_id UUID NOT NULL
        CHECK (expected_current_lifecycle_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    retained_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (realm_id, catalog_revision),
    CHECK (catalog_revision = expected_catalog_revision + 1)
);

CREATE TABLE game_session_current_private_catalog_revisions (
    realm_id UUID PRIMARY KEY REFERENCES game_session_canonical_realm_catalog(realm_id) ON DELETE RESTRICT,
    catalog_revision BIGINT NOT NULL CHECK (catalog_revision > 0),
    revision_request_id UUID NOT NULL,
    preparation_request_id UUID NOT NULL,
    playtest_lifecycle_id UUID NOT NULL
);

-- [jooq ignore start]
CREATE FUNCTION game_session_private_catalog_revision_no_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Private catalog revision history is immutable and retained' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER game_session_private_catalog_revision_immutable BEFORE UPDATE OR DELETE
    ON game_session_private_catalog_revision_history FOR EACH ROW
    EXECUTE FUNCTION game_session_private_catalog_revision_no_mutation();
CREATE TRIGGER game_session_private_catalog_revision_no_truncate BEFORE TRUNCATE
    ON game_session_private_catalog_revision_history FOR EACH STATEMENT
    EXECUTE FUNCTION game_session_private_catalog_revision_no_mutation();

CREATE TRIGGER game_session_current_private_catalog_revision_no_delete BEFORE DELETE
    ON game_session_current_private_catalog_revisions FOR EACH ROW
    EXECUTE FUNCTION game_session_private_catalog_revision_no_mutation();
CREATE TRIGGER game_session_current_private_catalog_revision_no_truncate BEFORE TRUNCATE
    ON game_session_current_private_catalog_revisions FOR EACH STATEMENT
    EXECUTE FUNCTION game_session_private_catalog_revision_no_mutation();

CREATE FUNCTION game_session_private_catalog_revision_head_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE original game_session_canonical_realm_catalog%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO original FROM game_session_canonical_realm_catalog
         WHERE realm_id = NEW.realm_id AND private_preparation_request_id IS NOT NULL
         FOR SHARE;
        IF NOT FOUND OR NEW.catalog_revision <> 1
            OR NEW.revision_request_id IS DISTINCT FROM original.creation_request_id
            OR NEW.preparation_request_id IS DISTINCT FROM original.private_preparation_request_id
            OR NEW.playtest_lifecycle_id IS DISTINCT FROM original.private_playtest_lifecycle_id THEN
            RAISE EXCEPTION 'Initial private catalog head must exactly name its retained revision-one allocation'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.realm_id IS DISTINCT FROM OLD.realm_id
        OR NEW.catalog_revision <> OLD.catalog_revision + 1
        OR NOT EXISTS (
            SELECT 1 FROM game_session_private_catalog_revision_history revision
             WHERE revision.realm_id = NEW.realm_id
               AND revision.catalog_revision = NEW.catalog_revision
               AND revision.request_id = NEW.revision_request_id
               AND revision.preparation_request_id = NEW.preparation_request_id
               AND revision.playtest_lifecycle_id = NEW.playtest_lifecycle_id
               AND revision.expected_catalog_revision = OLD.catalog_revision
               AND revision.expected_current_lifecycle_id = OLD.playtest_lifecycle_id
        ) THEN
        RAISE EXCEPTION 'Private catalog head must advance exactly once to its matching retained revision'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_private_catalog_revision_head_guard BEFORE INSERT OR UPDATE
    ON game_session_current_private_catalog_revisions FOR EACH ROW
    EXECUTE FUNCTION game_session_private_catalog_revision_head_guard();

CREATE FUNCTION game_session_private_catalog_revision_head_commit_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.catalog_revision = 1 THEN
        IF NOT EXISTS (
            SELECT 1 FROM game_session_canonical_realm_catalog original
             WHERE original.realm_id = NEW.realm_id
               AND original.catalog_revision = 1
               AND original.private_preparation_request_id IS NOT NULL
               AND original.creation_request_id = NEW.revision_request_id
               AND original.private_preparation_request_id = NEW.preparation_request_id
               AND original.private_playtest_lifecycle_id = NEW.playtest_lifecycle_id
        ) THEN
            RAISE EXCEPTION 'Private catalog revision-one head must resolve to its exact original allocation'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1 FROM game_session_private_catalog_revision_history revision
         WHERE revision.realm_id = NEW.realm_id
           AND revision.catalog_revision = NEW.catalog_revision
           AND revision.request_id = NEW.revision_request_id
           AND revision.preparation_request_id = NEW.preparation_request_id
           AND revision.playtest_lifecycle_id = NEW.playtest_lifecycle_id
    ) THEN
        RAISE EXCEPTION 'Private catalog head must resolve to its exact retained lifecycle revision'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER game_session_private_catalog_revision_head_commit_guard
    AFTER INSERT OR UPDATE ON game_session_current_private_catalog_revisions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION game_session_private_catalog_revision_head_commit_guard();

CREATE FUNCTION game_session_private_catalog_revision_original_claim_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.private_preparation_request_id IS NULL THEN
        RETURN NEW;
    END IF;
    -- Use the same owner keys as private FRESH allocation/revision requests. This serializes
    -- cross-journal identity claims without changing the caller-owned SERIALIZABLE boundary.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'private-fresh-preparation/' || NEW.private_preparation_request_id::TEXT, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'private-fresh-request/' || NEW.creation_request_id::TEXT, 0));
    IF EXISTS (
        SELECT 1 FROM game_session_private_catalog_revision_history revision
         WHERE revision.request_id = NEW.creation_request_id
            OR revision.preparation_request_id = NEW.private_preparation_request_id
            OR revision.playtest_lifecycle_id = NEW.private_playtest_lifecycle_id
            OR revision.playable_state_namespace_id = NEW.playable_state_namespace_id
    ) THEN
        RAISE EXCEPTION 'Original private catalog allocation reuses retained lifecycle revision identity'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_private_catalog_revision_original_claim_guard
    BEFORE INSERT ON game_session_canonical_realm_catalog FOR EACH ROW
    EXECUTE FUNCTION game_session_private_catalog_revision_original_claim_guard();

CREATE FUNCTION game_session_private_catalog_revision_head_initializer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.private_preparation_request_id IS NOT NULL THEN
        INSERT INTO game_session_current_private_catalog_revisions
            (realm_id, catalog_revision, revision_request_id, preparation_request_id, playtest_lifecycle_id)
        VALUES (NEW.realm_id, 1, NEW.creation_request_id, NEW.private_preparation_request_id,
            NEW.private_playtest_lifecycle_id);
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER game_session_private_catalog_revision_head_initializer
    AFTER INSERT ON game_session_canonical_realm_catalog FOR EACH ROW
    EXECUTE FUNCTION game_session_private_catalog_revision_head_initializer();

CREATE FUNCTION game_session_private_catalog_revision_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    head game_session_current_private_catalog_revisions%ROWTYPE;
    original game_session_canonical_realm_catalog%ROWTYPE;
    preparation game_session_initial_playtest_preparations%ROWTYPE;
    source game_session_canonical_realm_catalog%ROWTYPE;
    original_preparation game_session_initial_playtest_preparations%ROWTYPE;
    original_source game_session_canonical_realm_catalog%ROWTYPE;
    current_namespace UUID;
    fields TEXT[];
    item TEXT;
    framed BYTEA := ''::BYTEA;
    receipt_framed BYTEA := ''::BYTEA;
    original_fields TEXT[];
    original_item TEXT;
    original_framed BYTEA := ''::BYTEA;
    original_receipt_framed BYTEA := ''::BYTEA;
BEGIN
    -- Keep cross-journal claims serialized under the names already used by private FRESH.
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'private-fresh-preparation/' || NEW.preparation_request_id::TEXT, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended(
        'private-fresh-request/' || NEW.request_id::TEXT, 0));

    fields := ARRAY['game-session-private-catalog-revision-request/v1', NEW.request_id::TEXT,
        NEW.realm_id::TEXT, NEW.preparation_request_id::TEXT, NEW.playtest_lifecycle_id::TEXT,
        NEW.expected_catalog_revision::TEXT, NEW.expected_current_lifecycle_id::TEXT];
    SELECT * INTO STRICT preparation FROM game_session_initial_playtest_preparations
     WHERE request_id = NEW.preparation_request_id
       AND playtest_lifecycle_id = NEW.playtest_lifecycle_id
     FOR SHARE;
    fields := fields || ARRAY[preparation.request_digest];
    FOREACH item IN ARRAY fields LOOP
        framed := framed || int4send(octet_length(convert_to(item, 'UTF8'))) || convert_to(item, 'UTF8');
    END LOOP;
    IF NEW.request_preimage IS DISTINCT FROM framed
        OR NEW.request_digest IS DISTINCT FROM 'sha256:' || encode(sha256(framed), 'hex') THEN
        RAISE EXCEPTION 'Private catalog revision request bytes differ from exact immutable intent'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO head FROM game_session_current_private_catalog_revisions
     WHERE realm_id = NEW.realm_id FOR UPDATE;
    IF NOT FOUND OR head.catalog_revision IS DISTINCT FROM NEW.expected_catalog_revision
        OR head.playtest_lifecycle_id IS DISTINCT FROM NEW.expected_current_lifecycle_id THEN
        RAISE EXCEPTION 'Private catalog revision expected head differs from current revision and lifecycle'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1 FROM game_session_private_catalog_revision_history revision
         WHERE revision.request_id = NEW.request_id
            OR revision.preparation_request_id = NEW.preparation_request_id
            OR revision.playtest_lifecycle_id = NEW.playtest_lifecycle_id
            OR revision.playable_state_namespace_id = preparation.playable_state_namespace_id
    ) OR EXISTS (
        SELECT 1 FROM game_session_canonical_realm_catalog prior
         WHERE prior.private_preparation_request_id = NEW.preparation_request_id
            OR (prior.private_preparation_request_id IS NOT NULL
                AND prior.creation_request_id = NEW.request_id)
    ) THEN
        RAISE EXCEPTION 'Private catalog revision reuses original or retained request, preparation, lifecycle or namespace identity'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO original FROM game_session_canonical_realm_catalog
     WHERE realm_id = NEW.realm_id AND private_preparation_request_id IS NOT NULL
     FOR SHARE;
    IF NOT FOUND OR original.catalog_revision <> 1
        OR original.target_namespace <> preparation.target_namespace
        OR original.canonical_tenant_id <> preparation.canonical_tenant_id
        OR original.world_slug <> preparation.world_slug
        OR original.realm_slug <> preparation.realm_slug
        OR original.visible OR original.public_production OR original.state_scope <> 'ISOLATED'
        OR original.private_state_generation <> 1
        OR original.private_preparation_request_id = NEW.preparation_request_id
        OR original.private_playtest_lifecycle_id = NEW.playtest_lifecycle_id
        OR original.private_target_request_preimage IS NULL
        OR original.request_digest <> 'sha256:' || encode(sha256(original.private_target_request_preimage), 'hex') THEN
        RAISE EXCEPTION 'Private catalog revision requires the immutable original stable revision-one realm identity'
            USING ERRCODE = '23514';
    END IF;

    -- Requalify the original V1056 allocation against its own preparation/source and receipt.
    -- Later lifecycle policy/build/request bytes are independent and must not equal these facts.
    SELECT * INTO STRICT original_preparation FROM game_session_initial_playtest_preparations
     WHERE request_id = original.private_preparation_request_id
       AND playtest_lifecycle_id = original.private_playtest_lifecycle_id
     FOR SHARE;
    SELECT * INTO STRICT original_source FROM game_session_canonical_realm_catalog
     WHERE target_namespace = original.target_namespace
       AND realm_id = original.private_source_catalog_realm_id
     FOR SHARE;
    original_fields := ARRAY['game-session-private-fresh-catalog-request/v1',
        original.creation_request_id::TEXT, original.private_preparation_request_id::TEXT,
        original.private_playtest_lifecycle_id::TEXT, original.private_preparation_request_digest];
    FOREACH original_item IN ARRAY original_fields LOOP
        original_framed := original_framed
            || int4send(octet_length(convert_to(original_item, 'UTF8'))) || convert_to(original_item, 'UTF8');
    END LOOP;
    IF original.private_target_request_preimage IS DISTINCT FROM original_framed
        OR original_preparation.initialization_mode <> 'FRESH'
        OR original_preparation.preparation_state <> 'PREPARING'
        OR original_preparation.playtest_state_generation <> 1
        OR original_preparation.request_digest <> original.private_preparation_request_digest
        OR original_preparation.target_namespace <> original.target_namespace
        OR original_preparation.canonical_tenant_id <> original.canonical_tenant_id
        OR original_preparation.world_slug <> original.world_slug
        OR original_preparation.realm_slug <> original.realm_slug
        OR NOT original_preparation.target_policy_visible
        OR original_preparation.target_policy_public_production
        OR original_preparation.target_policy_state_scope <> 'ISOLATED'
        OR original_preparation.target_policy_world_slug <> original.world_slug
        OR original_preparation.target_policy_realm_slug <> original.realm_slug
        OR original_preparation.playable_state_namespace_id <> original.playable_state_namespace_id
        OR original_preparation.source_catalog_realm_id <> original.private_source_catalog_realm_id
        OR original_preparation.source_catalog_request_id <> original.private_source_catalog_request_id
        OR original_preparation.source_catalog_revision <> original.private_source_catalog_revision
        OR original_preparation.source_catalog_receipt_digest <> original.private_source_catalog_receipt_digest
        OR original_preparation.source_intake_operation_id <> original.source_intake_operation_id
        OR original_preparation.source_intake_receipt_digest <> original.source_intake_receipt_digest
        OR original_preparation.source_operation_id <> original.source_operation_id
        OR original_preparation.source_evidence_digest <> original.source_evidence_digest
        OR original_preparation.target_policy_id <> original.private_target_policy_id
        OR original_preparation.target_policy_digest <> original.private_target_policy_digest
        OR original_preparation.target_policy_source_commit_id <> original.private_target_policy_source_commit_id
        OR original_preparation.target_policy_source_revision_id <> original.private_target_policy_source_revision_id
        OR original_preparation.target_policy_logical_revision_id <> original.private_target_policy_logical_revision_id
        OR original_preparation.canonical_version_id <> original.private_canonical_version_id
        OR original_preparation.published_release_bundle_ref <> original.private_published_release_bundle_ref
        OR original_preparation.published_release_bundle_digest <> original.private_published_release_bundle_digest
        OR original_preparation.source_policy_set_digest <> original.private_source_policy_set_digest
        OR original_preparation.source_policy_carrier_digest <> original.private_source_policy_carrier_digest
        OR original_preparation.target_policy_realm_display_name <> original.realm_display_name
        OR original_preparation.target_policy_entry_policy <> original.character_creation_policy
        OR original.private_state_generation <> original_preparation.playtest_state_generation
        OR original_source.visible IS DISTINCT FROM TRUE
        OR original_source.public_production IS DISTINCT FROM TRUE
        OR original_source.complete_policy_set_evidence IS NULL
        OR original_source.fixture_policy_request_bytes IS NOT NULL
        OR original_source.fixture_policy_seal_operation_id IS NOT NULL
        OR original_source.fixture_policy_seal_request_digest IS NOT NULL
        OR original_source.fixture_policy_seal_evidence_digest IS NOT NULL
        OR original_source.creation_request_id <> original.private_source_catalog_request_id
        OR original_source.catalog_revision <> original.private_source_catalog_revision
        OR original_source.receipt_digest <> original.private_source_catalog_receipt_digest
        OR original_source.canonical_tenant_id <> original.canonical_tenant_id
        OR original_source.tenant_slug <> original.tenant_slug
        OR original_source.world_slug <> original.world_slug
        OR original_source.realm_slug = original.realm_slug
        OR original_source.source_intake_operation_id <> original.source_intake_operation_id
        OR original_source.source_intake_schema_version <> original.source_intake_schema_version
        OR original_source.source_intake_request_id <> original.source_intake_request_id
        OR original_source.source_intake_request_digest <> original.source_intake_request_digest
        OR original_source.source_intake_receipt_digest <> original.source_intake_receipt_digest
        OR original_source.source_schema_version <> original.source_schema_version
        OR original_source.source_registration_request_id <> original.source_registration_request_id
        OR original_source.source_operation_id <> original.source_operation_id
        OR original_source.source_request_digest <> original.source_request_digest
        OR original_source.source_world_display_name <> original.source_world_display_name
        OR original_source.source_game_row_id <> original.source_game_row_id
        OR original_source.source_game_tenant_key <> original.source_game_tenant_key
        OR original_source.source_provenance_kind <> original.source_provenance_kind
        OR original_source.source_evidence_digest <> original.source_evidence_digest
        OR original.complete_policy_set_evidence IS DISTINCT FROM original_source.complete_policy_set_evidence
        OR original.private_source_policy_carrier_digest <>
            'sha256:' || encode(sha256(original_source.complete_policy_set_evidence), 'hex') THEN
        RAISE EXCEPTION 'Original private catalog allocation differs from its own exact retained V1056 preparation and Human source'
            USING ERRCODE = '23514';
    END IF;
    fields := ARRAY[
        'game-session-private-fresh-catalog-receipt/v1', original.creation_request_id::TEXT,
        original.request_digest, original.target_namespace, original.canonical_tenant_id::TEXT,
        original.tenant_slug, original.world_slug, original.realm_id::TEXT, original.realm_slug,
        original.realm_display_name, original.visible::TEXT, original.public_production::TEXT,
        original.state_scope, original.playable_state_namespace_id::TEXT,
        original.character_creation_policy, original.catalog_revision::TEXT,
        original.private_preparation_request_id::TEXT, original.private_playtest_lifecycle_id::TEXT,
        original.private_preparation_request_digest, original.private_state_generation::TEXT,
        original.private_source_catalog_realm_id::TEXT, original.private_source_catalog_request_id::TEXT,
        original.private_source_catalog_revision::TEXT, original.private_source_catalog_receipt_digest,
        original.private_target_policy_id::TEXT, original.private_target_policy_digest,
        original.private_target_policy_source_commit_id::TEXT, original.private_target_policy_source_revision_id::TEXT,
        original.private_target_policy_logical_revision_id, original.private_canonical_version_id::TEXT,
        original.private_published_release_bundle_ref, original.private_published_release_bundle_digest,
        original.private_source_policy_set_digest, original.private_source_policy_carrier_digest,
        original.source_intake_operation_id::TEXT, original.source_intake_receipt_digest];
    FOREACH item IN ARRAY fields LOOP
        original_receipt_framed := original_receipt_framed
            || int4send(octet_length(convert_to(item, 'UTF8'))) || convert_to(item, 'UTF8');
    END LOOP;
    IF original.receipt_digest <> 'sha256:' || encode(sha256(original_receipt_framed), 'hex') THEN
        RAISE EXCEPTION 'Original private catalog allocation receipt differs from its immutable V1056 outcome'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT source FROM game_session_canonical_realm_catalog
     WHERE target_namespace = preparation.target_namespace
       AND realm_id = preparation.source_catalog_realm_id
     FOR SHARE;
    IF NOT source.visible OR NOT source.public_production
        OR source.complete_policy_set_evidence IS NULL
        OR source.fixture_policy_request_bytes IS NOT NULL
        OR source.fixture_policy_seal_operation_id IS NOT NULL
        OR source.fixture_policy_seal_request_digest IS NOT NULL
        OR source.fixture_policy_seal_evidence_digest IS NOT NULL
        OR source.creation_request_id <> preparation.source_catalog_request_id
        OR source.catalog_revision <> preparation.source_catalog_revision
        OR source.receipt_digest <> preparation.source_catalog_receipt_digest
        OR source.canonical_tenant_id <> preparation.canonical_tenant_id
        OR source.world_slug <> preparation.world_slug
        OR source.realm_slug = preparation.realm_slug
        OR source.source_intake_operation_id <> preparation.source_intake_operation_id
        OR source.source_intake_receipt_digest <> preparation.source_intake_receipt_digest
        OR source.source_operation_id <> preparation.source_operation_id
        OR source.source_evidence_digest <> preparation.source_evidence_digest
        OR source.tenant_slug <> original.tenant_slug
        OR preparation.source_policy_carrier_digest <> 'sha256:' || encode(sha256(source.complete_policy_set_evidence), 'hex') THEN
        RAISE EXCEPTION 'Private catalog revision requires the exact retained complete Human-published source without fixture evidence'
            USING ERRCODE = '23514';
    END IF;

    IF preparation.initialization_mode <> 'FRESH'
        OR preparation.preparation_state <> 'PREPARING'
        OR preparation.playtest_state_generation <> 1
        OR NOT preparation.target_policy_visible
        OR preparation.target_policy_public_production
        OR preparation.target_policy_state_scope <> 'ISOLATED'
        OR preparation.target_policy_world_slug <> preparation.world_slug
        OR preparation.target_policy_realm_slug <> preparation.realm_slug
        OR preparation.target_namespace <> original.target_namespace
        OR preparation.canonical_tenant_id <> original.canonical_tenant_id
        OR preparation.world_slug <> original.world_slug
        OR preparation.realm_slug <> original.realm_slug
        OR NEW.playable_state_namespace_id <> preparation.playable_state_namespace_id
        OR preparation.playtest_lifecycle_id = head.playtest_lifecycle_id
        OR preparation.request_id = head.preparation_request_id
        OR preparation.fork_expiry_second + preparation.fork_expiry_nano / 1000000000.0
            <= extract(epoch FROM clock_timestamp()) THEN
        RAISE EXCEPTION 'Private catalog revision requires a new exact unexpired FRESH PREPARING lifecycle for the stable realm selectors'
            USING ERRCODE = '23514';
    END IF;

    IF head.catalog_revision = 1 THEN
        current_namespace := original.playable_state_namespace_id;
    ELSE
        SELECT revision.playable_state_namespace_id INTO current_namespace
          FROM game_session_private_catalog_revision_history revision
         WHERE revision.realm_id = head.realm_id AND revision.catalog_revision = head.catalog_revision
           AND revision.request_id = head.revision_request_id
           AND revision.preparation_request_id = head.preparation_request_id
           AND revision.playtest_lifecycle_id = head.playtest_lifecycle_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Current private catalog revision has no exact retained lifecycle source'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF preparation.playable_state_namespace_id = current_namespace
        OR EXISTS (SELECT 1 FROM game_session_canonical_realm_catalog catalog
            WHERE catalog.playable_state_namespace_id = preparation.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM game_session_private_catalog_revision_history revision
            WHERE revision.playable_state_namespace_id = preparation.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_tenant_shared_playable_state_namespace namespace
            WHERE namespace.playable_state_namespace_id = preparation.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_admission_pointer pointer
            WHERE pointer.playable_state_namespace_id = preparation.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_published_tenant_shared_playable_state_namespace namespace
            WHERE namespace.playable_state_namespace_id = preparation.playable_state_namespace_id)
        OR EXISTS (SELECT 1 FROM gameplay_published_realm_catalog_entry entry
            WHERE entry.playable_state_namespace_id = preparation.playable_state_namespace_id) THEN
        RAISE EXCEPTION 'Private catalog revision namespace must be fresh across retained catalog and namespace authority'
            USING ERRCODE = '23514';
    END IF;

    fields := ARRAY['game-session-private-catalog-revision-receipt/v1', NEW.request_id::TEXT,
        NEW.request_digest, NEW.realm_id::TEXT, NEW.catalog_revision::TEXT,
        preparation.target_namespace, preparation.canonical_tenant_id::TEXT,
        preparation.world_slug, preparation.realm_slug, preparation.request_id::TEXT,
        preparation.request_digest, preparation.playtest_lifecycle_id::TEXT,
        preparation.playable_state_namespace_id::TEXT, preparation.playtest_state_generation::TEXT,
        preparation.source_catalog_realm_id::TEXT, preparation.source_catalog_request_id::TEXT,
        preparation.source_catalog_revision::TEXT, preparation.source_catalog_receipt_digest,
        preparation.target_policy_id::TEXT, preparation.target_policy_digest,
        preparation.target_policy_source_commit_id::TEXT, preparation.target_policy_source_revision_id::TEXT,
        preparation.target_policy_logical_revision_id, preparation.canonical_version_id::TEXT,
        preparation.published_release_bundle_ref, preparation.published_release_bundle_digest,
        preparation.source_policy_set_digest, preparation.source_policy_carrier_digest,
        preparation.target_policy_realm_display_name, preparation.target_policy_entry_policy];
    FOREACH item IN ARRAY fields LOOP
        receipt_framed := receipt_framed || int4send(octet_length(convert_to(item, 'UTF8'))) || convert_to(item, 'UTF8');
    END LOOP;
    IF NEW.receipt_digest <> 'sha256:' || encode(sha256(receipt_framed), 'hex') THEN
        RAISE EXCEPTION 'Private catalog revision receipt digest differs from exact retained lifecycle evidence'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_private_catalog_revision_guard BEFORE INSERT
    ON game_session_private_catalog_revision_history FOR EACH ROW
    EXECUTE FUNCTION game_session_private_catalog_revision_guard();

CREATE FUNCTION game_session_private_catalog_revision_commit_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE preparation game_session_initial_playtest_preparations%ROWTYPE;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM game_session_current_private_catalog_revisions head
         WHERE head.realm_id = NEW.realm_id AND head.catalog_revision >= NEW.catalog_revision
    ) THEN
        RAISE EXCEPTION 'Private catalog revision and current head must commit together'
            USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT preparation FROM game_session_initial_playtest_preparations
     WHERE request_id = NEW.preparation_request_id AND playtest_lifecycle_id = NEW.playtest_lifecycle_id;
    IF preparation.fork_expiry_second + preparation.fork_expiry_nano / 1000000000.0
        <= extract(epoch FROM clock_timestamp()) THEN
        RAISE EXCEPTION 'Private catalog revision preparation expired before final commit'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER game_session_private_catalog_revision_commit_guard
    AFTER INSERT ON game_session_private_catalog_revision_history DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION game_session_private_catalog_revision_commit_guard();

-- Existing original revision-one rows gain a separately retained exact head; originals stay intact.
INSERT INTO game_session_current_private_catalog_revisions
    (realm_id, catalog_revision, revision_request_id, preparation_request_id, playtest_lifecycle_id)
SELECT realm_id, 1, creation_request_id, private_preparation_request_id, private_playtest_lifecycle_id
  FROM game_session_canonical_realm_catalog
 WHERE private_preparation_request_id IS NOT NULL;
-- [jooq ignore stop]

-- Both relations are source/control evidence only; neither is runtime payload or admission state.
INSERT INTO game_session_runtime_family_classification
    (family_id, relation_name, family_role, state_class, fresh_disposition, retention_disposition, owner_consequence)
VALUES
    ('game_session_private_catalog_revision_history', 'game_session_private_catalog_revision_history',
        'CONTROL', 'NOT_PAYLOAD', 'CONTROL_ONLY', 'IMMUTABLE_RETAINED',
        'Append-only private catalog lifecycle revision and receipt retain exact preparation/source evidence; never runtime payload, materialization, CLOSED aggregation or admission.'),
    ('game_session_current_private_catalog_revisions', 'game_session_current_private_catalog_revisions',
        'CONTROL', 'NOT_PAYLOAD', 'CONTROL_ONLY', 'OWNER_POLICY_RETAINED',
        'Current private catalog lifecycle revision CAS is source control only; preserve original revision and all selections/preparations, with no runtime, materialization or admission authority.');

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION gs_runtime_family_catalog_installed() RETURNS BOOLEAN
LANGUAGE SQL STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
    SELECT (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1057__game_session_owner_runtime_family_catalog.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1057'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1058__game_session_catalog_view_options.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1058'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1059__private_lifecycle_preparation_after_catalog_allocation.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1059'
    ) AND (
        SELECT count(*)=1 AND coalesce(bool_and(type='SQL'
            AND script='V1060__private_catalog_lifecycle_revisions.sql' AND success),false)
        FROM "${flyway:table}" WHERE version='1060'
    );
$$;

CREATE OR REPLACE FUNCTION gs_runtime_family_catalog_bytes(p_epoch BIGINT) RETURNS BYTEA
LANGUAGE plpgsql STABLE SET search_path = "${serviceSchema}", pg_catalog AS $$
DECLARE v_families JSONB; v_facts JSONB; v_gaps JSONB; v_bytes BYTEA;
BEGIN
    IF p_epoch IS NULL OR p_epoch<=0 THEN
        RAISE EXCEPTION 'Positive Game Session catalog epoch required' USING ERRCODE='23514';
    END IF;
    IF EXISTS (
        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='${serviceSchema}' AND c.relkind IN ('r','p','f')
        EXCEPT SELECT relation_name FROM game_session_runtime_family_classification WHERE component_name=''
    ) OR EXISTS (
        SELECT 1 FROM game_session_runtime_family_classification f
        LEFT JOIN pg_namespace n ON n.nspname='${serviceSchema}'
        LEFT JOIN pg_class c ON c.relnamespace=n.oid AND c.relname=f.relation_name AND c.relkind IN ('r','p','f')
        WHERE c.oid IS NULL OR (f.component_name<>'' AND NOT EXISTS (
            SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname=f.component_name
            AND a.attnum>0 AND NOT a.attisdropped))
    ) THEN
        RAISE EXCEPTION 'Unclassified or changed Game Session schema family' USING ERRCODE='23514';
    END IF;
    SELECT jsonb_agg(jsonb_build_object('familyId',family_id,'relation',relation_name,'component',component_name,
        'role',family_role,'stateClass',state_class,'freshDisposition',fresh_disposition,
        'retentionDisposition',retention_disposition,'ownerConsequence',owner_consequence)
        ORDER BY family_id COLLATE "C") INTO v_families FROM game_session_runtime_family_classification;
    SELECT jsonb_agg(jsonb_build_object('id',gap_id,'disposition',disposition,'ownerConsequence',owner_consequence)
        ORDER BY gap_id COLLATE "C") INTO v_gaps FROM game_session_runtime_catalog_non_sql_gap;

    -- Definitions only, not rows, OIDs, sequence positions, statistics or installation timestamps.
    WITH relations AS (
        SELECT c.* FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='${serviceSchema}'
    ), definitions(kind,identity,definition) AS (
        SELECT 'TABLE',c.relname::TEXT,jsonb_build_array(c.relkind,c.relpersistence,
            c.relrowsecurity,c.relforcerowsecurity,c.reloptions,pg_get_partkeydef(c.oid))::TEXT
        FROM relations c WHERE c.relkind IN ('r','p','f')
        UNION ALL
        SELECT 'COLUMN',c.relname||'.'||a.attname,jsonb_build_array(a.attnum,
            format_type(a.atttypid,a.atttypmod),a.attnotnull,a.attidentity,a.attgenerated,
            pg_get_expr(d.adbin,d.adrelid),co.collname)::TEXT
        FROM relations c JOIN pg_attribute a ON a.attrelid=c.oid
        LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
        LEFT JOIN pg_collation co ON co.oid=a.attcollation
        WHERE c.relkind IN ('r','p','f','v','m') AND a.attnum>0 AND NOT a.attisdropped
        UNION ALL
        SELECT 'CONSTRAINT',c.relname||'.'||k.conname,
            jsonb_build_array(pg_get_constraintdef(k.oid,true),k.convalidated,k.condeferrable,k.condeferred)::TEXT
        FROM relations c JOIN pg_constraint k ON k.conrelid=c.oid
        UNION ALL
        SELECT 'INDEX',c.relname::TEXT,jsonb_build_array(pg_get_indexdef(c.oid),i.indisvalid,i.indisready)::TEXT
        FROM relations c JOIN pg_index i ON i.indexrelid=c.oid
        UNION ALL
        SELECT 'TRIGGER',c.relname||'.'||t.tgname,jsonb_build_array(pg_get_triggerdef(t.oid,true),t.tgenabled)::TEXT
        FROM relations c JOIN pg_trigger t ON t.tgrelid=c.oid WHERE NOT t.tgisinternal
        UNION ALL
        SELECT 'POLICY',c.relname||'.'||p.polname,jsonb_build_array(p.polcmd,p.polpermissive,
            (SELECT jsonb_agg(r.rolname ORDER BY r.rolname COLLATE "C") FROM pg_roles r WHERE r.oid=ANY(p.polroles)),
            pg_get_expr(p.polqual,p.polrelid),pg_get_expr(p.polwithcheck,p.polrelid))::TEXT
        FROM relations c JOIN pg_policy p ON p.polrelid=c.oid
        UNION ALL
        SELECT 'RULE',c.relname||'.'||r.rulename,pg_get_ruledef(r.oid,true)
        FROM relations c JOIN pg_rewrite r ON r.ev_class=c.oid
        UNION ALL
        SELECT 'ROUTINE',p.proname||'('||pg_get_function_identity_arguments(p.oid)||')',pg_get_functiondef(p.oid)
        FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
        WHERE n.nspname='${serviceSchema}' AND p.prokind IN ('f','p')
        UNION ALL
        SELECT 'SEQUENCE',c.relname::TEXT,(to_jsonb(s)-'seqrelid')::TEXT
        FROM relations c JOIN pg_sequence s ON s.seqrelid=c.oid
        UNION ALL
        SELECT 'VIEW',c.relname::TEXT,jsonb_build_array(pg_get_viewdef(c.oid,true),c.reloptions)::TEXT
        FROM relations c WHERE c.relkind IN ('v','m')
        UNION ALL
        SELECT 'TYPE',t.typname::TEXT,jsonb_build_array(t.typtype,t.typnotnull,t.typdefault,
            (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid))::TEXT
        FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='${serviceSchema}'
        UNION ALL
        SELECT 'MIGRATION',installed_rank::TEXT||':'||coalesce(version,'repeatable')||':'||script,
            jsonb_build_array(version,type,script,checksum,success)::TEXT
        FROM "${flyway:table}" WHERE version IS DISTINCT FROM '1057' AND version IS DISTINCT FROM '1058'
            AND version IS DISTINCT FROM '1059' AND version IS DISTINCT FROM '1060'
        UNION ALL
        -- The four installer rows are independently required as exact successful SQL scripts.
        -- Stable markers bind their installation without inventing future Flyway checksums.
        SELECT 'MIGRATION','catalog-installation:1057','V1057__game_session_owner_runtime_family_catalog.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1058','V1058__game_session_catalog_view_options.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1059','V1059__private_lifecycle_preparation_after_catalog_allocation.sql'
        UNION ALL
        SELECT 'MIGRATION','catalog-installation:1060','V1060__private_catalog_lifecycle_revisions.sql'
    )
    SELECT jsonb_agg(jsonb_build_object('kind',kind,'identity',identity,
        'definitionDigest','sha256:'||encode(sha256(convert_to(definition,'UTF8')),'hex'))
        ORDER BY (kind||'/'||identity) COLLATE "C") INTO v_facts FROM definitions;
    IF v_families IS NULL OR v_facts IS NULL OR v_gaps IS NULL
        OR jsonb_array_length(v_families)>512 OR jsonb_array_length(v_facts)>16384
        OR jsonb_array_length(v_gaps)>32 THEN
        RAISE EXCEPTION 'Complete Game Session catalog exceeds source bounds' USING ERRCODE='23514';
    END IF;
    v_bytes := convert_to(jsonb_build_object('schema','game-session-runtime-family-catalog/v1',
        'owner','game-session-service','catalogEpoch',p_epoch::TEXT,
        'families',v_families,'schemaFacts',v_facts,'nonSqlGaps',v_gaps)::TEXT,'UTF8');
    IF octet_length(v_bytes)>2097152 THEN
        RAISE EXCEPTION 'Complete Game Session catalog exceeds byte bound' USING ERRCODE='23514';
    END IF;
    RETURN v_bytes;
END;
$$;

-- Seal epoch 4 only after the DDL, both guards and the complete classifications are installed.
INSERT INTO game_session_runtime_family_catalog_epoch(catalog_epoch,catalog_bytes,catalog_digest)
SELECT 4,bytes,'sha256:'||encode(sha256(bytes),'hex')
FROM (SELECT gs_runtime_family_catalog_bytes(4) AS bytes) seal;
-- [jooq ignore stop]
