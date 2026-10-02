-- Preserve every retained pointer and audit value as representation version 1. New canonical
-- closed-only rows use version 2 and leave legacy numeric and catalog-policy copies absent.
-- [jooq ignore start]
ALTER TABLE gameplay_admission_pointer
    ADD COLUMN representation_version integer NOT NULL DEFAULT 1,
    ADD COLUMN target_namespace character varying(63),
    ADD COLUMN canonical_tenant_id uuid,
    ADD COLUMN admission_state character varying(16);

ALTER TABLE gameplay_admission_pointer
    ALTER COLUMN tenant_id DROP NOT NULL,
    ALTER COLUMN game_instance_id DROP NOT NULL,
    ALTER COLUMN world_display_name DROP NOT NULL,
    ALTER COLUMN realm_display_name DROP NOT NULL,
    ALTER COLUMN visible DROP NOT NULL,
    ALTER COLUMN public_production_realm DROP NOT NULL,
    ALTER COLUMN public_production_realm DROP DEFAULT,
    ALTER COLUMN requires_character_selection DROP NOT NULL,
    ALTER COLUMN state_scope DROP NOT NULL,
    ALTER COLUMN character_creation_policy DROP NOT NULL,
    ALTER COLUMN last_updated_by DROP NOT NULL,
    ALTER COLUMN last_update_reason DROP NOT NULL;

ALTER TABLE gameplay_admission_pointer
    DROP CONSTRAINT gameplay_admission_pointer_identity_pair_complete;

ALTER TABLE gameplay_admission_pointer
    ADD CONSTRAINT chk_gameplay_admission_pointer_representation CHECK (
        (representation_version = 1
            AND target_namespace IS NULL
            AND canonical_tenant_id IS NULL
            AND admission_state IS NULL
            AND tenant_id IS NOT NULL
            AND game_instance_id IS NOT NULL
            AND world_display_name IS NOT NULL
            AND realm_display_name IS NOT NULL
            AND visible IS NOT NULL
            AND public_production_realm IS NOT NULL
            AND requires_character_selection IS NOT NULL
            AND state_scope IS NOT NULL
            AND character_creation_policy IS NOT NULL
            AND last_updated_by IS NOT NULL
            AND last_update_reason IS NOT NULL
            AND ((realm_id IS NULL) = (playable_state_namespace_id IS NULL)))
        OR (representation_version = 2
            AND target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND realm_id IS NOT NULL
            AND playable_state_namespace_id IS NULL
            AND admission_state IS NOT NULL
            AND admission_state = 'CLOSED'
            AND tenant_id IS NULL
            AND game_instance_id IS NULL
            AND pointer_version = 1
            AND catalog_revision = 1
            AND world_display_name IS NULL
            AND realm_display_name IS NULL
            AND visible IS NULL
            AND public_production_realm IS NULL
            AND requires_character_selection IS NULL
            AND state_scope IS NULL
            AND character_creation_policy IS NULL
            AND last_updated_by IS NULL
            AND last_update_reason IS NULL))
    ,
    ADD CONSTRAINT chk_gameplay_admission_pointer_canonical_identity CHECK (
        representation_version <> 2
        OR (octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid))
    ,
    ADD CONSTRAINT fk_gameplay_admission_pointer_canonical_catalog
        FOREIGN KEY (target_namespace, realm_id)
        REFERENCES game_session_canonical_realm_catalog (target_namespace, realm_id);

ALTER TABLE gameplay_admission_pointer_event
    ADD COLUMN representation_version integer NOT NULL DEFAULT 1,
    ADD COLUMN target_namespace character varying(63),
    ADD COLUMN canonical_tenant_id uuid,
    ADD COLUMN realm_id uuid,
    ADD COLUMN catalog_revision bigint,
    ADD COLUMN admission_state character varying(16);

ALTER TABLE gameplay_admission_pointer_event
    ALTER COLUMN tenant_id DROP NOT NULL,
    ALTER COLUMN game_instance_id DROP NOT NULL,
    ALTER COLUMN world_display_name DROP NOT NULL,
    ALTER COLUMN realm_display_name DROP NOT NULL,
    ALTER COLUMN visible DROP NOT NULL,
    ALTER COLUMN public_production_realm DROP NOT NULL,
    ALTER COLUMN public_production_realm DROP DEFAULT,
    ALTER COLUMN requires_character_selection DROP NOT NULL,
    ALTER COLUMN state_scope DROP NOT NULL,
    ALTER COLUMN character_creation_policy DROP NOT NULL;

ALTER TABLE gameplay_admission_pointer_event
    ADD CONSTRAINT chk_gameplay_admission_pointer_event_representation CHECK (
        (representation_version = 1
            AND target_namespace IS NULL
            AND canonical_tenant_id IS NULL
            AND realm_id IS NULL
            AND catalog_revision IS NULL
            AND admission_state IS NULL
            AND tenant_id IS NOT NULL
            AND game_instance_id IS NOT NULL
            AND world_display_name IS NOT NULL
            AND realm_display_name IS NOT NULL
            AND visible IS NOT NULL
            AND public_production_realm IS NOT NULL
            AND requires_character_selection IS NOT NULL
            AND state_scope IS NOT NULL
            AND character_creation_policy IS NOT NULL)
        OR (representation_version = 2
            AND target_namespace IS NOT NULL
            AND canonical_tenant_id IS NOT NULL
            AND realm_id IS NOT NULL
            AND catalog_revision IS NOT NULL
            AND catalog_revision = 1
            AND admission_state IS NOT NULL
            AND admission_state = 'CLOSED'
            AND tenant_id IS NULL
            AND game_instance_id IS NULL
            AND pointer_version = 1
            AND world_display_name IS NULL
            AND realm_display_name IS NULL
            AND visible IS NULL
            AND public_production_realm IS NULL
            AND requires_character_selection IS NULL
            AND state_scope IS NULL
            AND character_creation_policy IS NULL
            AND prepared_version_upgrade_id IS NULL))
    ,
    ADD CONSTRAINT chk_gameplay_admission_pointer_event_canonical_identity CHECK (
        representation_version <> 2
        OR (octet_length(target_namespace) BETWEEN 1 AND 63
            AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid))
    ,
    ADD CONSTRAINT fk_gameplay_admission_pointer_event_canonical_catalog
        FOREIGN KEY (target_namespace, realm_id)
        REFERENCES game_session_canonical_realm_catalog (target_namespace, realm_id);

CREATE UNIQUE INDEX uq_gameplay_admission_pointer_event_canonical_realm_version
    ON gameplay_admission_pointer_event (target_namespace, canonical_tenant_id, realm_id, pointer_version)
    WHERE representation_version = 2;

CREATE TABLE game_session_canonical_closed_admission_pointer_request (
    target_namespace character varying(63) NOT NULL,
    request_id uuid NOT NULL,
    request_digest character varying(71) NOT NULL,
    canonical_tenant_id uuid NOT NULL,
    realm_id uuid NOT NULL,
    catalog_creation_request_id uuid NOT NULL,
    catalog_revision bigint NOT NULL,
    catalog_request_digest character varying(71) NOT NULL,
    catalog_receipt_digest character varying(71) NOT NULL,
    actor_principal character varying(200) NOT NULL,
    reason character varying(500) NOT NULL,
    admission_state character varying(16) NOT NULL,
    pointer_version bigint NOT NULL,
    audit_event_id bigint NOT NULL,
    receipt_digest character varying(71) NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_gs_canonical_closed_pointer_request PRIMARY KEY (target_namespace, request_id),
    CONSTRAINT uq_gs_canonical_closed_pointer_request_realm
        UNIQUE (target_namespace, canonical_tenant_id, realm_id),
    CONSTRAINT uq_gs_canonical_closed_pointer_request_event UNIQUE (audit_event_id),
    CONSTRAINT fk_gs_canonical_closed_pointer_request_catalog
        FOREIGN KEY (target_namespace, realm_id)
        REFERENCES game_session_canonical_realm_catalog (target_namespace, realm_id),
    CONSTRAINT fk_gs_canonical_closed_pointer_request_event
        FOREIGN KEY (audit_event_id) REFERENCES gameplay_admission_pointer_event (id),
    CONSTRAINT chk_gs_canonical_closed_pointer_request_identity CHECK (
        octet_length(target_namespace) BETWEEN 1 AND 63
        AND target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        AND request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND realm_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND catalog_creation_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
        AND catalog_revision = 1
        AND pointer_version = 1
        AND audit_event_id > 0
        AND admission_state = 'CLOSED'),
    CONSTRAINT chk_gs_canonical_closed_pointer_request_digests CHECK (
        request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND catalog_request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND catalog_receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_gs_canonical_closed_pointer_request_audit CHECK (
        char_length(actor_principal) BETWEEN 1 AND 200
        AND actor_principal !~ '^[[:space:]]*$'
        AND char_length(reason) BETWEEN 1 AND 500
        AND reason !~ '^[[:space:]]*$')
);

CREATE FUNCTION reject_game_session_canonical_closed_pointer_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'TRUNCATE' THEN
        IF TG_TABLE_NAME = 'game_session_canonical_closed_admission_pointer_request'
            AND EXISTS (SELECT 1 FROM game_session_canonical_closed_admission_pointer_request)
        THEN
            RAISE EXCEPTION 'Canonical CLOSED pointer request outcomes are immutable survivor evidence'
                USING ERRCODE = '23514';
        ELSIF TG_TABLE_NAME = 'gameplay_admission_pointer'
            AND EXISTS (
                SELECT 1 FROM gameplay_admission_pointer WHERE representation_version = 2)
        THEN
            RAISE EXCEPTION 'Canonical CLOSED admission pointers are immutable survivor evidence'
                USING ERRCODE = '23514';
        ELSIF TG_TABLE_NAME = 'gameplay_admission_pointer_event'
            AND EXISTS (
                SELECT 1 FROM gameplay_admission_pointer_event WHERE representation_version = 2)
        THEN
            RAISE EXCEPTION 'Canonical CLOSED pointer events are immutable survivor evidence'
                USING ERRCODE = '23514';
        END IF;
        RETURN NULL;
    END IF;

    IF TG_TABLE_NAME = 'game_session_canonical_closed_admission_pointer_request' THEN
        RAISE EXCEPTION 'Canonical CLOSED pointer evidence is immutable'
            USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'gameplay_admission_pointer'
        OR TG_TABLE_NAME = 'gameplay_admission_pointer_event'
    THEN
        IF OLD.representation_version = 2
            OR (TG_OP = 'UPDATE' AND NEW.representation_version = 2)
        THEN
            RAISE EXCEPTION 'Canonical CLOSED pointer evidence is immutable'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gameplay_admission_pointer_canonical_immutable
BEFORE UPDATE OR DELETE ON gameplay_admission_pointer
FOR EACH ROW EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();

CREATE TRIGGER gameplay_admission_pointer_canonical_no_truncate
BEFORE TRUNCATE ON gameplay_admission_pointer
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();

CREATE TRIGGER gameplay_admission_pointer_event_canonical_immutable
BEFORE UPDATE OR DELETE ON gameplay_admission_pointer_event
FOR EACH ROW EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();

CREATE TRIGGER gameplay_admission_pointer_event_canonical_no_truncate
BEFORE TRUNCATE ON gameplay_admission_pointer_event
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();

CREATE TRIGGER game_session_canonical_closed_pointer_request_immutable
BEFORE UPDATE OR DELETE ON game_session_canonical_closed_admission_pointer_request
FOR EACH ROW EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();

CREATE TRIGGER game_session_canonical_closed_pointer_request_no_truncate
BEFORE TRUNCATE ON game_session_canonical_closed_admission_pointer_request
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_canonical_closed_pointer_mutation();

CREATE FUNCTION require_game_session_canonical_closed_pointer_catalog()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.representation_version = 2 AND NOT EXISTS (
        SELECT 1
        FROM game_session_canonical_realm_catalog catalog
        WHERE catalog.target_namespace = NEW.target_namespace
          AND catalog.canonical_tenant_id = NEW.canonical_tenant_id
          AND catalog.realm_id = NEW.realm_id
          AND catalog.world_slug = NEW.world_slug
          AND catalog.realm_slug = NEW.realm_slug
          AND catalog.catalog_revision = NEW.catalog_revision
          AND catalog.visible
          AND catalog.public_production
    ) THEN
        RAISE EXCEPTION 'Canonical CLOSED pointer requires its exact initial public catalog row'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.representation_version = 2 AND EXISTS (
        SELECT 1
        FROM game_session_retained_tenant_association retained
        WHERE retained.target_namespace = NEW.target_namespace
          AND retained.canonical_tenant_id = NEW.canonical_tenant_id
    ) THEN
        RAISE EXCEPTION 'Canonical CLOSED pointer cannot replace retained numeric tenant authority'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER gameplay_admission_pointer_canonical_requires_catalog
BEFORE INSERT ON gameplay_admission_pointer
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_closed_pointer_catalog();

CREATE FUNCTION require_game_session_canonical_closed_pointer_atomic_evidence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    expected_target_namespace character varying(63);
    expected_tenant_id uuid;
    expected_realm_id uuid;
    expected_pointer_version bigint;
    expected_event_id bigint;
    expected_request_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'gameplay_admission_pointer' THEN
        IF NEW.representation_version <> 2 THEN
            RETURN NULL;
        END IF;
        expected_target_namespace := NEW.target_namespace;
        expected_tenant_id := NEW.canonical_tenant_id;
        expected_realm_id := NEW.realm_id;
        expected_pointer_version := NEW.pointer_version;
    ELSIF TG_TABLE_NAME = 'gameplay_admission_pointer_event' THEN
        IF NEW.representation_version <> 2 THEN
            RETURN NULL;
        END IF;
        expected_target_namespace := NEW.target_namespace;
        expected_tenant_id := NEW.canonical_tenant_id;
        expected_realm_id := NEW.realm_id;
        expected_pointer_version := NEW.pointer_version;
        expected_event_id := NEW.id;
    ELSE
        expected_target_namespace := NEW.target_namespace;
        expected_tenant_id := NEW.canonical_tenant_id;
        expected_realm_id := NEW.realm_id;
        expected_pointer_version := NEW.pointer_version;
        expected_event_id := NEW.audit_event_id;
        expected_request_id := NEW.request_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM gameplay_admission_pointer pointer
        JOIN gameplay_admission_pointer_event event
          ON event.representation_version = 2
         AND event.target_namespace = pointer.target_namespace
         AND event.canonical_tenant_id = pointer.canonical_tenant_id
         AND event.realm_id = pointer.realm_id
         AND event.pointer_version = pointer.pointer_version
        JOIN game_session_canonical_closed_admission_pointer_request outcome
          ON outcome.target_namespace = pointer.target_namespace
         AND outcome.canonical_tenant_id = pointer.canonical_tenant_id
         AND outcome.realm_id = pointer.realm_id
         AND outcome.pointer_version = pointer.pointer_version
         AND outcome.audit_event_id = event.id
        JOIN game_session_canonical_realm_catalog catalog
          ON catalog.target_namespace = pointer.target_namespace
         AND catalog.canonical_tenant_id = pointer.canonical_tenant_id
         AND catalog.realm_id = pointer.realm_id
         AND catalog.catalog_revision = pointer.catalog_revision
         AND catalog.creation_request_id = outcome.catalog_creation_request_id
         AND catalog.request_digest = outcome.catalog_request_digest
         AND catalog.receipt_digest = outcome.catalog_receipt_digest
         AND catalog.visible
         AND catalog.public_production
        WHERE pointer.representation_version = 2
          AND pointer.target_namespace = expected_target_namespace
          AND pointer.canonical_tenant_id = expected_tenant_id
          AND pointer.realm_id = expected_realm_id
          AND pointer.pointer_version = expected_pointer_version
          AND pointer.admission_state = 'CLOSED'
          AND pointer.tenant_id IS NULL
          AND pointer.game_instance_id IS NULL
          AND pointer.playable_state_namespace_id IS NULL
          AND pointer.world_slug = catalog.world_slug
          AND pointer.realm_slug = catalog.realm_slug
          AND pointer.world_display_name IS NULL
          AND pointer.realm_display_name IS NULL
          AND pointer.visible IS NULL
          AND pointer.public_production_realm IS NULL
          AND pointer.requires_character_selection IS NULL
          AND pointer.state_scope IS NULL
          AND pointer.character_creation_policy IS NULL
          AND pointer.last_updated_by IS NULL
          AND pointer.last_update_reason IS NULL
          AND event.id = outcome.audit_event_id
          AND event.target_namespace = pointer.target_namespace
          AND event.canonical_tenant_id = pointer.canonical_tenant_id
          AND event.realm_id = pointer.realm_id
          AND event.pointer_version = pointer.pointer_version
          AND event.catalog_revision = catalog.catalog_revision
          AND event.admission_state = 'CLOSED'
          AND event.tenant_id IS NULL
          AND event.game_instance_id IS NULL
          AND event.world_slug = catalog.world_slug
          AND event.realm_slug = catalog.realm_slug
          AND event.world_display_name IS NULL
          AND event.realm_display_name IS NULL
          AND event.visible IS NULL
          AND event.public_production_realm IS NULL
          AND event.requires_character_selection IS NULL
          AND event.state_scope IS NULL
          AND event.character_creation_policy IS NULL
          AND event.prepared_version_upgrade_id IS NULL
          AND event.control_plane_request_id = outcome.request_id::text
          AND event.actor_principal = outcome.actor_principal
          AND event.reason = outcome.reason
          AND outcome.admission_state = 'CLOSED'
          AND outcome.request_digest ~ '^sha256:[0-9a-f]{64}$'
          AND outcome.receipt_digest ~ '^sha256:[0-9a-f]{64}$'
          AND outcome.pointer_version = pointer.pointer_version
          AND outcome.catalog_revision = catalog.catalog_revision
          AND outcome.target_namespace = pointer.target_namespace
          AND outcome.canonical_tenant_id = pointer.canonical_tenant_id
          AND outcome.realm_id = pointer.realm_id
          AND outcome.updated_at = (pointer.updated_at AT TIME ZONE 'UTC')
          AND outcome.updated_at = (pointer.created_at AT TIME ZONE 'UTC')
          AND event.occurred_at = (outcome.updated_at AT TIME ZONE 'UTC')
          AND (expected_event_id IS NULL OR event.id = expected_event_id)
          AND (expected_request_id IS NULL OR outcome.request_id = expected_request_id)
    ) THEN
        RAISE EXCEPTION 'Canonical CLOSED pointer, event, outcome and exact catalog must commit together'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER gameplay_admission_pointer_canonical_atomic_evidence
AFTER INSERT ON gameplay_admission_pointer
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_closed_pointer_atomic_evidence();

CREATE CONSTRAINT TRIGGER gameplay_admission_pointer_event_canonical_atomic_evidence
AFTER INSERT ON gameplay_admission_pointer_event
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_closed_pointer_atomic_evidence();

CREATE CONSTRAINT TRIGGER game_session_canonical_closed_pointer_request_atomic_evidence
AFTER INSERT ON game_session_canonical_closed_admission_pointer_request
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_game_session_canonical_closed_pointer_atomic_evidence();
-- [jooq ignore stop]
