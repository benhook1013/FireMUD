-- A private FRESH catalog identity is a typed, non-public continuation of one exact retained
-- preparation. It remains PREPARING and creates no runtime or admission authority.
ALTER TABLE game_session_initial_playtest_preparations
    ADD CONSTRAINT uq_gs_initial_playtest_private_catalog_binding
        UNIQUE (request_id, playtest_lifecycle_id);

ALTER TABLE game_session_canonical_realm_catalog
    ADD COLUMN private_target_request_preimage BYTEA,
    ADD COLUMN private_preparation_request_id UUID,
    ADD COLUMN private_playtest_lifecycle_id UUID,
    ADD COLUMN private_preparation_request_digest VARCHAR(71),
    ADD COLUMN private_state_generation BIGINT,
    ADD COLUMN private_source_catalog_realm_id UUID,
    ADD COLUMN private_source_catalog_request_id UUID,
    ADD COLUMN private_source_catalog_revision BIGINT,
    ADD COLUMN private_source_catalog_receipt_digest VARCHAR(71),
    ADD COLUMN private_target_policy_id UUID,
    ADD COLUMN private_target_policy_digest VARCHAR(71),
    ADD COLUMN private_target_policy_source_commit_id UUID,
    ADD COLUMN private_target_policy_source_revision_id UUID,
    ADD COLUMN private_target_policy_logical_revision_id VARCHAR(128),
    ADD COLUMN private_canonical_version_id UUID,
    ADD COLUMN private_published_release_bundle_ref VARCHAR(4096),
    ADD COLUMN private_published_release_bundle_digest VARCHAR(71),
    ADD COLUMN private_source_policy_set_digest VARCHAR(71),
    ADD COLUMN private_source_policy_carrier_digest VARCHAR(71),
    ADD CONSTRAINT fk_gs_private_fresh_catalog_preparation
        FOREIGN KEY (private_preparation_request_id, private_playtest_lifecycle_id)
        REFERENCES game_session_initial_playtest_preparations (request_id, playtest_lifecycle_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_gs_private_fresh_catalog_source_catalog
        FOREIGN KEY (target_namespace, private_source_catalog_realm_id)
        REFERENCES game_session_canonical_realm_catalog (target_namespace, realm_id)
        ON DELETE RESTRICT;

ALTER TABLE game_session_canonical_realm_catalog
    DROP CONSTRAINT chk_gs_canonical_realm_catalog_public_initial;
ALTER TABLE game_session_canonical_realm_catalog
    DROP CONSTRAINT chk_gs_canonical_realm_catalog_names;

ALTER TABLE game_session_canonical_realm_catalog
    ADD CONSTRAINT chk_gs_canonical_realm_catalog_public_initial CHECK (
        (visible AND public_production AND private_preparation_request_id IS NULL
            AND private_target_request_preimage IS NULL
            AND private_playtest_lifecycle_id IS NULL)
        OR (NOT visible AND NOT public_production AND state_scope = 'ISOLATED'
            AND private_preparation_request_id IS NOT NULL
            AND private_target_request_preimage IS NOT NULL
            AND private_playtest_lifecycle_id IS NOT NULL)
    ),
    ADD CONSTRAINT chk_gs_canonical_realm_catalog_names CHECK (
        (
            ((private_preparation_request_id IS NULL
                  AND char_length(realm_display_name) BETWEEN 1 AND 100
                  AND octet_length(realm_display_name) <= 400)
                OR (private_preparation_request_id IS NOT NULL
                    AND char_length(realm_display_name) BETWEEN 1 AND 128
                    AND octet_length(realm_display_name) <= 512))
            AND char_length(realm_display_name) BETWEEN 1 AND 128
            AND char_length(source_world_display_name) BETWEEN 1 AND 100
            AND octet_length(source_world_display_name) <= 400
            AND realm_display_name !~ '^[[:space:]]*$'
            AND source_world_display_name !~ '^[[:space:]]*$'
        )
    );

-- [jooq ignore start]
-- PostgreSQL bytea SHA-256 and wire-preimage predicates have no H2 DDL export equivalent.
-- The columns above remain visible to generation; physical PostgreSQL proof owns this check.
ALTER TABLE game_session_canonical_realm_catalog
    ADD CONSTRAINT chk_gs_private_fresh_catalog_binding CHECK (
        (private_preparation_request_id IS NULL
            AND private_target_request_preimage IS NULL
            AND private_playtest_lifecycle_id IS NULL
            AND private_preparation_request_digest IS NULL
            AND private_state_generation IS NULL
            AND private_source_catalog_realm_id IS NULL
            AND private_source_catalog_request_id IS NULL
            AND private_source_catalog_revision IS NULL
            AND private_source_catalog_receipt_digest IS NULL
            AND private_target_policy_id IS NULL
            AND private_target_policy_digest IS NULL
            AND private_target_policy_source_commit_id IS NULL
            AND private_target_policy_source_revision_id IS NULL
            AND private_target_policy_logical_revision_id IS NULL
            AND private_canonical_version_id IS NULL
            AND private_published_release_bundle_ref IS NULL
            AND private_published_release_bundle_digest IS NULL
            AND private_source_policy_set_digest IS NULL
            AND private_source_policy_carrier_digest IS NULL)
        OR
        (private_preparation_request_id IS NOT NULL
            AND private_target_request_preimage IS NOT NULL
            AND octet_length(private_target_request_preimage) BETWEEN 1 AND 4096
            AND request_digest = 'sha256:' || encode(sha256(private_target_request_preimage), 'hex')
            AND private_playtest_lifecycle_id IS NOT NULL
            AND private_preparation_request_digest IS NOT NULL
            AND private_preparation_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND private_state_generation IS NOT NULL
            AND private_state_generation = 1
            AND private_source_catalog_realm_id IS NOT NULL
            AND private_source_catalog_realm_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND private_source_catalog_request_id IS NOT NULL
            AND private_source_catalog_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND private_source_catalog_revision IS NOT NULL
            AND private_source_catalog_revision = 1
            AND private_source_catalog_receipt_digest IS NOT NULL
            AND private_source_catalog_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
            AND private_target_policy_id IS NOT NULL
            AND private_target_policy_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND private_target_policy_digest IS NOT NULL
            AND private_target_policy_digest ~ '^sha256:[0-9a-f]{64}$'
            AND private_target_policy_source_commit_id IS NOT NULL
            AND private_target_policy_source_commit_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND private_target_policy_source_revision_id IS NOT NULL
            AND private_target_policy_source_revision_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND private_target_policy_logical_revision_id IS NOT NULL
            AND char_length(private_target_policy_logical_revision_id) BETWEEN 1 AND 128
            AND private_target_policy_logical_revision_id !~ '^[[:space:]]*$'
            AND private_canonical_version_id IS NOT NULL
            AND private_canonical_version_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND private_published_release_bundle_ref IS NOT NULL
            AND char_length(private_published_release_bundle_ref) BETWEEN 1 AND 1024
            AND octet_length(private_published_release_bundle_ref) <= 4096
            AND private_published_release_bundle_ref !~ '^[[:space:]]*$'
            AND private_published_release_bundle_digest IS NOT NULL
            AND private_published_release_bundle_digest ~ '^sha256:[0-9a-f]{64}$'
            AND private_source_policy_set_digest IS NOT NULL
            AND private_source_policy_set_digest ~ '^sha256:[0-9a-f]{64}$'
            AND private_source_policy_carrier_digest IS NOT NULL
            AND private_source_policy_carrier_digest ~ '^sha256:[0-9a-f]{64}$'
            AND NOT visible AND NOT public_production AND state_scope = 'ISOLATED'
            AND catalog_revision = 1
            AND realm_id <> private_source_catalog_realm_id
            AND realm_id <> private_target_policy_id
            AND realm_id <> private_playtest_lifecycle_id
            AND realm_id <> playable_state_namespace_id
            AND complete_policy_set_evidence IS NOT NULL
            AND fixture_policy_request_bytes IS NULL
            AND fixture_policy_seal_operation_id IS NULL
            AND fixture_policy_seal_request_digest IS NULL
            AND fixture_policy_seal_evidence_digest IS NULL)
    );
-- [jooq ignore stop]

CREATE UNIQUE INDEX uq_gs_private_fresh_catalog_preparation
    ON game_session_canonical_realm_catalog (private_preparation_request_id)
    WHERE private_preparation_request_id IS NOT NULL;

CREATE UNIQUE INDEX uq_gs_private_fresh_catalog_request
    ON game_session_canonical_realm_catalog (creation_request_id)
    WHERE private_preparation_request_id IS NOT NULL;

-- [jooq ignore start]
CREATE FUNCTION require_game_session_private_fresh_catalog_target()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    preparation game_session_initial_playtest_preparations%ROWTYPE;
    source game_session_canonical_realm_catalog%ROWTYPE;
    fields TEXT[];
    item TEXT;
    receipt_framed BYTEA := ''::BYTEA;
BEGIN
    IF NEW.private_preparation_request_id IS NULL THEN
        IF NEW.private_target_request_preimage IS NOT NULL
            OR NEW.private_playtest_lifecycle_id IS NOT NULL
            OR NEW.private_preparation_request_digest IS NOT NULL
            OR NEW.private_state_generation IS NOT NULL
            OR NEW.private_source_catalog_realm_id IS NOT NULL
            OR NEW.private_source_catalog_request_id IS NOT NULL
            OR NEW.private_source_catalog_revision IS NOT NULL
            OR NEW.private_source_catalog_receipt_digest IS NOT NULL
            OR NEW.private_target_policy_id IS NOT NULL
            OR NEW.private_target_policy_digest IS NOT NULL
            OR NEW.private_target_policy_source_commit_id IS NOT NULL
            OR NEW.private_target_policy_source_revision_id IS NOT NULL
            OR NEW.private_target_policy_logical_revision_id IS NOT NULL
            OR NEW.private_canonical_version_id IS NOT NULL
            OR NEW.private_published_release_bundle_ref IS NOT NULL
            OR NEW.private_published_release_bundle_digest IS NOT NULL
            OR NEW.private_source_policy_set_digest IS NOT NULL
            OR NEW.private_source_policy_carrier_digest IS NOT NULL THEN
            RAISE EXCEPTION 'Public canonical catalog row cannot carry private FRESH preparation provenance'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    SELECT * INTO STRICT preparation
      FROM game_session_initial_playtest_preparations
     WHERE request_id = NEW.private_preparation_request_id
       AND playtest_lifecycle_id = NEW.private_playtest_lifecycle_id
     FOR SHARE;
    IF preparation.initialization_mode <> 'FRESH'
        OR preparation.preparation_state <> 'PREPARING'
        OR preparation.playtest_state_generation <> 1
        OR preparation.request_digest <> NEW.private_preparation_request_digest
        OR preparation.target_namespace <> NEW.target_namespace
        OR preparation.canonical_tenant_id <> NEW.canonical_tenant_id
        OR preparation.world_slug <> NEW.world_slug
        OR preparation.realm_slug <> NEW.realm_slug
        OR preparation.playable_state_namespace_id <> NEW.playable_state_namespace_id
        OR preparation.target_policy_public_production
        OR NOT preparation.target_policy_visible
        OR preparation.target_policy_state_scope <> 'ISOLATED'
        OR preparation.target_policy_world_slug <> NEW.world_slug
        OR preparation.target_policy_realm_slug <> NEW.realm_slug
        OR preparation.target_policy_realm_display_name <> NEW.realm_display_name
        OR preparation.target_policy_entry_policy <> NEW.character_creation_policy
        OR preparation.source_catalog_realm_id <> NEW.private_source_catalog_realm_id
        OR preparation.source_catalog_request_id <> NEW.private_source_catalog_request_id
        OR preparation.source_catalog_revision <> NEW.private_source_catalog_revision
        OR preparation.source_catalog_receipt_digest <> NEW.private_source_catalog_receipt_digest
        OR preparation.source_intake_operation_id <> NEW.source_intake_operation_id
        OR preparation.source_intake_receipt_digest <> NEW.source_intake_receipt_digest
        OR preparation.source_operation_id <> NEW.source_operation_id
        OR preparation.source_evidence_digest <> NEW.source_evidence_digest
        OR preparation.target_policy_id <> NEW.private_target_policy_id
        OR preparation.target_policy_digest <> NEW.private_target_policy_digest
        OR preparation.target_policy_source_commit_id <> NEW.private_target_policy_source_commit_id
        OR preparation.target_policy_source_revision_id <> NEW.private_target_policy_source_revision_id
        OR preparation.target_policy_logical_revision_id <> NEW.private_target_policy_logical_revision_id
        OR preparation.canonical_version_id <> NEW.private_canonical_version_id
        OR preparation.published_release_bundle_ref <> NEW.private_published_release_bundle_ref
        OR preparation.published_release_bundle_digest <> NEW.private_published_release_bundle_digest
        OR preparation.source_policy_set_digest <> NEW.private_source_policy_set_digest
        OR preparation.source_policy_carrier_digest <> NEW.private_source_policy_carrier_digest
        OR NEW.private_target_request_preimage IS DISTINCT FROM (
            int4send(octet_length(convert_to('game-session-private-fresh-catalog-request/v1', 'UTF8')))
                || convert_to('game-session-private-fresh-catalog-request/v1', 'UTF8')
            || int4send(octet_length(convert_to(NEW.creation_request_id::TEXT, 'UTF8'))) || convert_to(NEW.creation_request_id::TEXT, 'UTF8')
            || int4send(octet_length(convert_to(NEW.private_preparation_request_id::TEXT, 'UTF8'))) || convert_to(NEW.private_preparation_request_id::TEXT, 'UTF8')
            || int4send(octet_length(convert_to(NEW.private_playtest_lifecycle_id::TEXT, 'UTF8'))) || convert_to(NEW.private_playtest_lifecycle_id::TEXT, 'UTF8')
            || int4send(octet_length(convert_to(NEW.private_preparation_request_digest, 'UTF8'))) || convert_to(NEW.private_preparation_request_digest, 'UTF8'))
        OR NEW.realm_id = NEW.private_source_catalog_realm_id
        OR NEW.realm_id = NEW.private_target_policy_id
        OR NEW.realm_id = NEW.private_playtest_lifecycle_id
        OR NEW.realm_id = NEW.playable_state_namespace_id
        OR NEW.visible OR NEW.public_production OR NEW.state_scope <> 'ISOLATED'
        OR NEW.catalog_revision <> 1
        OR NEW.complete_policy_set_evidence IS NULL
        OR NEW.fixture_policy_request_bytes IS NOT NULL
        OR NEW.fixture_policy_seal_operation_id IS NOT NULL
        OR NEW.fixture_policy_seal_request_digest IS NOT NULL
        OR NEW.fixture_policy_seal_evidence_digest IS NOT NULL THEN
        RAISE EXCEPTION 'Private FRESH catalog row differs from its exact retained preparation'
            USING ERRCODE = '23514';
    END IF;

    SELECT * INTO STRICT source
      FROM game_session_canonical_realm_catalog
     WHERE target_namespace = preparation.target_namespace
       AND canonical_tenant_id = preparation.canonical_tenant_id
       AND realm_id = preparation.source_catalog_realm_id
     FOR SHARE;
    IF NOT source.visible OR NOT source.public_production
        OR source.complete_policy_set_evidence IS NULL
        OR source.fixture_policy_request_bytes IS NOT NULL
        OR source.fixture_policy_seal_operation_id IS NOT NULL
        OR source.fixture_policy_seal_request_digest IS NOT NULL
        OR source.fixture_policy_seal_evidence_digest IS NOT NULL
        OR source.creation_request_id <> NEW.private_source_catalog_request_id
        OR source.catalog_revision <> NEW.private_source_catalog_revision
        OR source.receipt_digest <> NEW.private_source_catalog_receipt_digest
        OR source.canonical_tenant_id <> preparation.canonical_tenant_id
        OR source.world_slug <> preparation.world_slug
        OR source.complete_policy_set_evidence IS DISTINCT FROM NEW.complete_policy_set_evidence
        OR source.source_intake_operation_id <> NEW.source_intake_operation_id
        OR source.source_intake_schema_version <> NEW.source_intake_schema_version
        OR source.source_intake_request_id <> NEW.source_intake_request_id
        OR source.source_intake_request_digest <> NEW.source_intake_request_digest
        OR source.source_intake_receipt_digest <> NEW.source_intake_receipt_digest
        OR source.source_schema_version <> NEW.source_schema_version
        OR source.source_registration_request_id <> NEW.source_registration_request_id
        OR source.source_operation_id <> NEW.source_operation_id
        OR source.source_request_digest <> NEW.source_request_digest
        OR source.source_world_display_name <> NEW.source_world_display_name
        OR source.source_game_row_id <> NEW.source_game_row_id
        OR source.source_game_tenant_key <> NEW.source_game_tenant_key
        OR source.source_provenance_kind <> NEW.source_provenance_kind
        OR source.source_evidence_digest <> NEW.source_evidence_digest
        OR NEW.private_target_policy_source_commit_id = '00000000-0000-0000-0000-000000000000'::UUID
        OR NEW.private_source_policy_carrier_digest <> 'sha256:' || encode(sha256(source.complete_policy_set_evidence), 'hex') THEN
        RAISE EXCEPTION 'Private FRESH catalog source differs from exact retained Human publication evidence'
            USING ERRCODE = '23514';
    END IF;

    fields := ARRAY[
        'game-session-private-fresh-catalog-receipt/v1', NEW.creation_request_id::TEXT,
        NEW.request_digest, NEW.target_namespace, NEW.canonical_tenant_id::TEXT, NEW.tenant_slug,
        NEW.world_slug, NEW.realm_id::TEXT, NEW.realm_slug, NEW.realm_display_name,
        NEW.visible::TEXT, NEW.public_production::TEXT, NEW.state_scope,
        NEW.playable_state_namespace_id::TEXT, NEW.character_creation_policy,
        NEW.catalog_revision::TEXT, NEW.private_preparation_request_id::TEXT,
        NEW.private_playtest_lifecycle_id::TEXT, NEW.private_preparation_request_digest,
        NEW.private_state_generation::TEXT, NEW.private_source_catalog_realm_id::TEXT,
        NEW.private_source_catalog_request_id::TEXT, NEW.private_source_catalog_revision::TEXT,
        NEW.private_source_catalog_receipt_digest, NEW.private_target_policy_id::TEXT,
        NEW.private_target_policy_digest, NEW.private_target_policy_source_commit_id::TEXT,
        NEW.private_target_policy_source_revision_id::TEXT,
        NEW.private_target_policy_logical_revision_id, NEW.private_canonical_version_id::TEXT,
        NEW.private_published_release_bundle_ref, NEW.private_published_release_bundle_digest,
        NEW.private_source_policy_set_digest, NEW.private_source_policy_carrier_digest,
        NEW.source_intake_operation_id::TEXT, NEW.source_intake_receipt_digest];
    FOREACH item IN ARRAY fields LOOP
        receipt_framed := receipt_framed || int4send(octet_length(convert_to(item, 'UTF8'))) || convert_to(item, 'UTF8');
    END LOOP;
    IF NEW.receipt_digest <> 'sha256:' || encode(sha256(receipt_framed), 'hex') THEN
        RAISE EXCEPTION 'Private FRESH catalog receipt digest differs from its persisted outcome'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER game_session_private_fresh_catalog_target_guard
    BEFORE INSERT ON game_session_canonical_realm_catalog
    FOR EACH ROW EXECUTE FUNCTION require_game_session_private_fresh_catalog_target();

CREATE FUNCTION game_session_private_fresh_catalog_commit_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE preparation game_session_initial_playtest_preparations%ROWTYPE;
BEGIN
    IF NEW.private_preparation_request_id IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT * INTO STRICT preparation
      FROM game_session_initial_playtest_preparations
     WHERE request_id = NEW.private_preparation_request_id
       AND playtest_lifecycle_id = NEW.private_playtest_lifecycle_id;
    IF preparation.fork_expiry_second + preparation.fork_expiry_nano / 1000000000.0
        <= extract(epoch FROM clock_timestamp()) THEN
        RAISE EXCEPTION 'Private FRESH catalog preparation expired before final catalog commit'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER game_session_private_fresh_catalog_commit_guard
    AFTER INSERT ON game_session_canonical_realm_catalog
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION game_session_private_fresh_catalog_commit_guard();
-- [jooq ignore stop]
