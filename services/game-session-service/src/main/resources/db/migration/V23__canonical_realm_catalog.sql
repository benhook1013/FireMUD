-- Preserve all retained numeric keys and namespace bytes. The namespace UUID is the stable
-- primary identity; tenant_id remains nullable/unique for the existing numeric runtime selector.
-- [jooq ignore start]
-- This PostgreSQL-only identity reshape replaces V6's implicit constraint name. H2's jOOQ schema
-- export assigns a different generated name; no generated typed binding consumes these columns.
ALTER TABLE gameplay_tenant_shared_playable_state_namespace
    DROP CONSTRAINT gameplay_tenant_shared_playable_state_namespace_pkey;

ALTER TABLE gameplay_tenant_shared_playable_state_namespace
    ALTER COLUMN tenant_id DROP NOT NULL;

ALTER TABLE gameplay_tenant_shared_playable_state_namespace
    ADD CONSTRAINT pk_gameplay_tenant_shared_playable_state_namespace
        PRIMARY KEY (playable_state_namespace_id),
    ADD CONSTRAINT uq_gameplay_tenant_shared_namespace_legacy_tenant
        UNIQUE (tenant_id),
    ADD COLUMN canonical_tenant_id uuid,
    ADD COLUMN target_namespace character varying(63),
    ADD COLUMN source_intake_operation_id uuid,
    ADD COLUMN source_intake_schema_version integer,
    ADD COLUMN source_intake_request_id uuid,
    ADD COLUMN source_intake_request_digest character varying(71),
    ADD COLUMN source_intake_receipt_digest character varying(71),
    ADD COLUMN source_schema_version integer,
    ADD COLUMN source_registration_request_id uuid,
    ADD COLUMN source_operation_id uuid,
    ADD COLUMN source_request_digest character varying(71),
    ADD COLUMN source_game_row_id bigint,
    ADD COLUMN source_game_tenant_key character varying(36),
    ADD COLUMN source_provenance_kind character varying(32),
    ADD COLUMN source_evidence_digest character varying(71),
    ADD CONSTRAINT uq_gameplay_tenant_shared_namespace_canonical_tenant
        UNIQUE (canonical_tenant_id),
    ADD CONSTRAINT fk_gameplay_tenant_shared_namespace_tenant_source
        FOREIGN KEY (target_namespace, canonical_tenant_id)
        REFERENCES game_session_authored_world_tenant_source_binding
            (target_namespace, canonical_tenant_id),
    ADD CONSTRAINT fk_gameplay_tenant_shared_namespace_source_intake
        FOREIGN KEY (source_intake_operation_id)
        REFERENCES game_session_authored_world_source_intake (operation_id),
    ADD CONSTRAINT chk_gameplay_tenant_shared_namespace_non_nil
        CHECK (
            canonical_tenant_id IS NULL
            OR playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        ),
    ADD CONSTRAINT chk_gameplay_tenant_shared_namespace_identity_mode CHECK (
        (tenant_id IS NOT NULL
            AND canonical_tenant_id IS NULL
            AND target_namespace IS NULL
            AND source_intake_operation_id IS NULL
            AND source_intake_schema_version IS NULL
            AND source_intake_request_id IS NULL
            AND source_intake_request_digest IS NULL
            AND source_intake_receipt_digest IS NULL
            AND source_schema_version IS NULL
            AND source_registration_request_id IS NULL
            AND source_operation_id IS NULL
            AND source_request_digest IS NULL
            AND source_game_row_id IS NULL
            AND source_game_tenant_key IS NULL
            AND source_provenance_kind IS NULL
            AND source_evidence_digest IS NULL)
        OR (tenant_id IS NULL
            AND canonical_tenant_id IS NOT NULL
            AND target_namespace IS NOT NULL
            AND source_intake_operation_id IS NOT NULL
            AND source_intake_schema_version IS NOT NULL
            AND source_intake_schema_version = 1
            AND source_intake_request_id IS NOT NULL
            AND source_intake_request_digest IS NOT NULL
            AND source_intake_receipt_digest IS NOT NULL
            AND source_schema_version IS NOT NULL
            AND source_schema_version = 1
            AND source_registration_request_id IS NOT NULL
            AND source_operation_id IS NOT NULL
            AND source_request_digest IS NOT NULL
            AND source_game_row_id IS NOT NULL
            AND source_game_tenant_key IS NOT NULL
            AND source_provenance_kind IS NOT NULL
            AND source_provenance_kind = 'NEW_GAME_ROW'
            AND source_evidence_digest IS NOT NULL)
    ),
    ADD CONSTRAINT chk_gameplay_tenant_shared_namespace_fresh_source CHECK (
        canonical_tenant_id IS NULL
        OR (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND source_intake_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND source_intake_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND source_registration_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND source_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_intake_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_intake_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_game_row_id > 0
            AND char_length(source_game_tenant_key) BETWEEN 1 AND 36
            AND source_game_tenant_key !~ '^[[:space:]]*$')
    );
-- [jooq ignore stop]

CREATE TABLE game_session_canonical_realm_catalog (
    target_namespace character varying(63) NOT NULL,
    canonical_tenant_id uuid NOT NULL,
    tenant_slug character varying(120) NOT NULL,
    world_slug character varying(120) NOT NULL,
    realm_id uuid NOT NULL,
    realm_slug character varying(120) NOT NULL,
    realm_display_name character varying(200) NOT NULL,
    visible boolean NOT NULL,
    public_production boolean NOT NULL,
    state_scope character varying(16) NOT NULL,
    playable_state_namespace_id uuid NOT NULL,
    character_creation_policy character varying(64) NOT NULL,
    catalog_revision bigint NOT NULL,
    creation_request_id uuid NOT NULL,
    request_digest character varying(71) NOT NULL,
    receipt_digest character varying(71) NOT NULL,
    source_intake_operation_id uuid NOT NULL,
    source_intake_schema_version integer NOT NULL,
    source_intake_request_id uuid NOT NULL,
    source_intake_request_digest character varying(71) NOT NULL,
    source_intake_receipt_digest character varying(71) NOT NULL,
    source_schema_version integer NOT NULL,
    source_registration_request_id uuid NOT NULL,
    source_operation_id uuid NOT NULL,
    source_request_digest character varying(71) NOT NULL,
    source_world_display_name character varying(400) NOT NULL,
    source_game_row_id bigint NOT NULL,
    source_game_tenant_key character varying(36) NOT NULL,
    source_provenance_kind character varying(32) NOT NULL,
    source_evidence_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_canonical_realm_catalog
        PRIMARY KEY (target_namespace, realm_id),
    CONSTRAINT uq_gs_canonical_realm_catalog_realm_id
        UNIQUE (realm_id),
    CONSTRAINT uq_gs_canonical_realm_catalog_request
        UNIQUE (target_namespace, creation_request_id),
    CONSTRAINT uq_gs_canonical_realm_catalog_tenant_slug
        UNIQUE (target_namespace, canonical_tenant_id, realm_slug),
    CONSTRAINT fk_gs_canonical_realm_catalog_tenant_source
        FOREIGN KEY (
            target_namespace,
            canonical_tenant_id,
            tenant_slug,
            source_game_row_id,
            source_game_tenant_key,
            source_provenance_kind
        )
        REFERENCES game_session_authored_world_tenant_source_binding (
            target_namespace,
            canonical_tenant_id,
            tenant_slug,
            source_game_row_id,
            source_game_tenant_key,
            provenance_kind
        ),
    CONSTRAINT fk_gs_canonical_realm_catalog_source_intake
        FOREIGN KEY (source_intake_operation_id)
        REFERENCES game_session_authored_world_source_intake (operation_id),
    CONSTRAINT chk_gs_canonical_realm_catalog_namespace CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_ids CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND playable_state_namespace_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND creation_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND source_intake_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND source_intake_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND source_registration_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_selector CHECK (
        octet_length(tenant_slug) BETWEEN 1 AND 120
        AND tenant_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        AND octet_length(world_slug) BETWEEN 1 AND 120
        AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        AND char_length(realm_slug) BETWEEN 1 AND 120
        AND octet_length(realm_slug) BETWEEN 1 AND 120
        AND realm_slug !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_public_initial CHECK (
        visible AND public_production
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_scope CHECK (
        state_scope IN ('SHARED', 'ISOLATED')
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_revision CHECK (catalog_revision = 1),
    CONSTRAINT chk_gs_canonical_realm_catalog_policy CHECK (
        char_length(character_creation_policy) BETWEEN 1 AND 64
        AND octet_length(character_creation_policy) <= 256
        AND character_creation_policy !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_names CHECK (
        char_length(realm_display_name) BETWEEN 1 AND 100
        AND octet_length(realm_display_name) <= 400
        AND realm_display_name !~ '^[[:space:]]*$'
        AND char_length(source_world_display_name) BETWEEN 1 AND 100
        AND octet_length(source_world_display_name) <= 400
        AND source_world_display_name !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_versions CHECK (
        source_intake_schema_version = 1
        AND source_schema_version = 1
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_source_key CHECK (
        source_game_row_id > 0
        AND char_length(source_game_tenant_key) BETWEEN 1 AND 36
        AND source_game_tenant_key !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_provenance CHECK (
        source_provenance_kind = 'NEW_GAME_ROW'
    ),
    CONSTRAINT chk_gs_canonical_realm_catalog_digests CHECK (
        request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_intake_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_intake_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
    )
);

CREATE UNIQUE INDEX uq_gs_canonical_realm_catalog_public_tenant
    ON game_session_canonical_realm_catalog (target_namespace, canonical_tenant_id)
    WHERE public_production;

CREATE UNIQUE INDEX uq_gs_canonical_realm_catalog_isolated_namespace
    ON game_session_canonical_realm_catalog (playable_state_namespace_id)
    WHERE state_scope = 'ISOLATED';

-- [jooq ignore start]
CREATE FUNCTION reject_game_session_canonical_realm_catalog_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Game Session canonical realm catalog evidence is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE FUNCTION protect_fresh_shared_namespace_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.canonical_tenant_id IS NOT NULL OR NEW.canonical_tenant_id IS NOT NULL THEN
        RAISE EXCEPTION 'Game Session fresh shared namespace identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_fresh_shared_namespace_immutable
BEFORE UPDATE OR DELETE ON gameplay_tenant_shared_playable_state_namespace
FOR EACH ROW EXECUTE FUNCTION protect_fresh_shared_namespace_identity();

CREATE TRIGGER game_session_canonical_realm_catalog_immutable
BEFORE UPDATE OR DELETE ON game_session_canonical_realm_catalog
FOR EACH ROW EXECUTE FUNCTION reject_game_session_canonical_realm_catalog_mutation();

CREATE TRIGGER game_session_canonical_realm_catalog_no_truncate
BEFORE TRUNCATE ON game_session_canonical_realm_catalog
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_realm_catalog_mutation();

CREATE FUNCTION require_game_session_canonical_realm_source_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended(NEW.target_namespace || ':' || NEW.canonical_tenant_id::text, 1));
    IF NOT EXISTS (
        SELECT 1
        FROM game_session_authored_world_source_intake source_intake
        WHERE source_intake.operation_id = NEW.source_intake_operation_id
          AND source_intake.schema_version = NEW.source_intake_schema_version
          AND source_intake.target_namespace = NEW.target_namespace
          AND source_intake.intake_request_id = NEW.source_intake_request_id
          AND source_intake.request_digest = NEW.source_intake_request_digest
          AND source_intake.source_schema_version = NEW.source_schema_version
          AND source_intake.source_registration_request_id = NEW.source_registration_request_id
          AND source_intake.source_operation_id = NEW.source_operation_id
          AND source_intake.source_request_digest = NEW.source_request_digest
          AND source_intake.canonical_tenant_id = NEW.canonical_tenant_id
          AND source_intake.tenant_slug = NEW.tenant_slug
          AND source_intake.world_slug = NEW.world_slug
          AND source_intake.world_display_name = NEW.source_world_display_name
          AND source_intake.source_game_row_id = NEW.source_game_row_id
          AND source_intake.source_game_tenant_key = NEW.source_game_tenant_key
          AND source_intake.source_provenance_kind = NEW.source_provenance_kind
          AND source_intake.source_evidence_digest = NEW.source_evidence_digest
          AND source_intake.receipt_digest = NEW.source_intake_receipt_digest
    ) THEN
        RAISE EXCEPTION 'Canonical realm catalog requires exact committed authored-world intake evidence'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM game_session_retained_tenant_association retained
        WHERE retained.target_namespace = NEW.target_namespace
          AND retained.canonical_tenant_id = NEW.canonical_tenant_id
    ) THEN
        RAISE EXCEPTION 'Canonical realm catalog cannot replace retained numeric tenant namespace authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_canonical_realm_catalog_requires_source
BEFORE INSERT ON game_session_canonical_realm_catalog
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_realm_source_evidence();

CREATE FUNCTION require_game_session_canonical_namespace_allocation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.playable_state_namespace_id::text, 0));
    IF NEW.state_scope = 'SHARED' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM gameplay_tenant_shared_playable_state_namespace shared_namespace
            WHERE shared_namespace.tenant_id IS NULL
              AND shared_namespace.canonical_tenant_id = NEW.canonical_tenant_id
              AND shared_namespace.target_namespace = NEW.target_namespace
              AND shared_namespace.playable_state_namespace_id = NEW.playable_state_namespace_id
        ) THEN
            RAISE EXCEPTION 'SHARED canonical realm requires its exact tenant namespace allocation'
                USING ERRCODE = '23514';
        END IF;
    ELSIF EXISTS (
        SELECT 1
        FROM gameplay_tenant_shared_playable_state_namespace shared_namespace
        WHERE shared_namespace.playable_state_namespace_id = NEW.playable_state_namespace_id
    ) THEN
            RAISE EXCEPTION 'ISOLATED canonical realm namespace conflicts with a tenant SHARED namespace'
                USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_canonical_catalog_requires_namespace
BEFORE INSERT ON game_session_canonical_realm_catalog
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_namespace_allocation();

CREATE FUNCTION require_game_session_shared_namespace_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.playable_state_namespace_id::text, 0));
    IF NEW.canonical_tenant_id IS NULL AND EXISTS (
        SELECT 1
        FROM game_session_canonical_realm_catalog catalog
        WHERE catalog.state_scope = 'ISOLATED'
          AND catalog.playable_state_namespace_id = NEW.playable_state_namespace_id
    ) THEN
        RAISE EXCEPTION 'Legacy tenant SHARED namespace conflicts with a canonical ISOLATED realm namespace'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.canonical_tenant_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM game_session_authored_world_source_intake source_intake
        WHERE source_intake.operation_id = NEW.source_intake_operation_id
          AND source_intake.schema_version = NEW.source_intake_schema_version
          AND source_intake.target_namespace = NEW.target_namespace
          AND source_intake.intake_request_id = NEW.source_intake_request_id
          AND source_intake.request_digest = NEW.source_intake_request_digest
          AND source_intake.source_schema_version = NEW.source_schema_version
          AND source_intake.source_registration_request_id = NEW.source_registration_request_id
          AND source_intake.source_operation_id = NEW.source_operation_id
          AND source_intake.source_request_digest = NEW.source_request_digest
          AND source_intake.canonical_tenant_id = NEW.canonical_tenant_id
          AND source_intake.source_game_row_id = NEW.source_game_row_id
          AND source_intake.source_game_tenant_key = NEW.source_game_tenant_key
          AND source_intake.source_provenance_kind = NEW.source_provenance_kind
          AND source_intake.source_evidence_digest = NEW.source_evidence_digest
          AND source_intake.receipt_digest = NEW.source_intake_receipt_digest
    ) THEN
        RAISE EXCEPTION 'Fresh SHARED namespace requires exact committed authored-world source receipt'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.canonical_tenant_id IS NOT NULL AND EXISTS (
        SELECT 1
        FROM game_session_retained_tenant_association retained
        WHERE retained.target_namespace = NEW.target_namespace
          AND retained.canonical_tenant_id = NEW.canonical_tenant_id
    ) THEN
        RAISE EXCEPTION 'Fresh SHARED namespace cannot replace retained numeric namespace authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_fresh_shared_namespace_requires_source
BEFORE INSERT OR UPDATE ON gameplay_tenant_shared_playable_state_namespace
FOR EACH ROW EXECUTE FUNCTION require_game_session_shared_namespace_source();

CREATE FUNCTION require_game_session_shared_namespace_catalog_reference()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.canonical_tenant_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM game_session_canonical_realm_catalog catalog
        WHERE catalog.state_scope = 'SHARED'
          AND catalog.target_namespace = NEW.target_namespace
          AND catalog.canonical_tenant_id = NEW.canonical_tenant_id
          AND catalog.playable_state_namespace_id = NEW.playable_state_namespace_id
    ) THEN
        RAISE EXCEPTION 'Fresh shared namespace cannot commit without its canonical realm catalog result'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER game_session_shared_namespace_requires_catalog
AFTER INSERT ON gameplay_tenant_shared_playable_state_namespace
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_game_session_shared_namespace_catalog_reference();

CREATE FUNCTION protect_fresh_shared_namespace_truncate()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM gameplay_tenant_shared_playable_state_namespace
        WHERE canonical_tenant_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Game Session fresh shared namespace identity is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER game_session_fresh_shared_namespace_no_truncate
BEFORE TRUNCATE ON gameplay_tenant_shared_playable_state_namespace
FOR EACH STATEMENT EXECUTE FUNCTION protect_fresh_shared_namespace_truncate();

-- A retained association can only be recorded for a tenant that has not already acquired fresh
-- canonical realm/SHARED namespace authority. Existing association capture takes SHARE locks on
-- the namespace family; this tenant-scoped advisory fence also serializes any owner-local insert
-- with the catalog's source-evidence trigger. Existing rows and exact retries are unaffected.
CREATE FUNCTION reject_retained_association_for_fresh_canonical_tenant()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended(NEW.target_namespace || ':' || NEW.canonical_tenant_id::text, 1));
    IF EXISTS (
        SELECT 1
        FROM game_session_canonical_realm_catalog catalog
        WHERE catalog.target_namespace = NEW.target_namespace
          AND catalog.canonical_tenant_id = NEW.canonical_tenant_id
    ) OR EXISTS (
        SELECT 1
        FROM gameplay_tenant_shared_playable_state_namespace shared_namespace
        WHERE shared_namespace.target_namespace = NEW.target_namespace
          AND shared_namespace.canonical_tenant_id = NEW.canonical_tenant_id
    ) THEN
        RAISE EXCEPTION 'Retained numeric association cannot replace fresh canonical tenant namespace authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER game_session_retained_association_rejects_fresh_tenant
BEFORE INSERT ON game_session_retained_tenant_association
FOR EACH ROW EXECUTE FUNCTION reject_retained_association_for_fresh_canonical_tenant();
-- [jooq ignore stop]
