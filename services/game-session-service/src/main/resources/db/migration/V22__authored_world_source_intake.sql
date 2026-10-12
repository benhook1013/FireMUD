CREATE TABLE game_session_authored_world_tenant_source_binding (
    target_namespace character varying(63) NOT NULL,
    canonical_tenant_id uuid NOT NULL,
    tenant_slug character varying(120) NOT NULL,
    source_game_row_id bigint NOT NULL,
    source_game_tenant_key character varying(36) NOT NULL,
    provenance_kind character varying(32) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_authored_world_tenant_source_binding
        PRIMARY KEY (target_namespace, canonical_tenant_id),
    CONSTRAINT uq_gs_authored_world_tenant_source_slug
        UNIQUE (target_namespace, tenant_slug),
    CONSTRAINT uq_gs_authored_world_tenant_source_row
        UNIQUE (target_namespace, source_game_row_id),
    CONSTRAINT uq_gs_authored_world_tenant_source_tuple
        UNIQUE (
            target_namespace,
            canonical_tenant_id,
            tenant_slug,
            source_game_row_id,
            source_game_tenant_key,
            provenance_kind
        ),
    CONSTRAINT chk_gs_authored_world_tenant_source_namespace
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT chk_gs_authored_world_tenant_source_tenant_id
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid),
    CONSTRAINT chk_gs_authored_world_tenant_source_slug
        CHECK (
            octet_length(tenant_slug) BETWEEN 1 AND 120
            AND tenant_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        ),
    CONSTRAINT chk_gs_authored_world_tenant_source_row_id
        CHECK (source_game_row_id > 0),
    CONSTRAINT chk_gs_authored_world_tenant_source_key
        CHECK (
            char_length(source_game_tenant_key) BETWEEN 1 AND 36
            AND source_game_tenant_key !~ '^[[:space:]]*$'
        ),
    CONSTRAINT chk_gs_authored_world_tenant_source_provenance
        CHECK (provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V30'))
);

CREATE TABLE game_session_authored_world_source_intake (
    operation_id uuid NOT NULL,
    schema_version integer NOT NULL,
    target_namespace character varying(63) NOT NULL,
    intake_request_id uuid NOT NULL,
    request_digest character varying(71) NOT NULL,
    source_schema_version integer NOT NULL,
    source_registration_request_id uuid NOT NULL,
    source_operation_id uuid NOT NULL,
    source_request_digest character varying(71) NOT NULL,
    canonical_tenant_id uuid NOT NULL,
    tenant_slug character varying(120) NOT NULL,
    world_slug character varying(120) NOT NULL,
    world_display_name character varying(400) NOT NULL,
    source_game_row_id bigint NOT NULL,
    source_game_tenant_key character varying(36) NOT NULL,
    source_provenance_kind character varying(32) NOT NULL,
    source_evidence_digest character varying(71) NOT NULL,
    receipt_digest character varying(71) NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gs_authored_world_source_intake PRIMARY KEY (operation_id),
    CONSTRAINT uq_gs_authored_world_source_intake_request
        UNIQUE (target_namespace, intake_request_id),
    CONSTRAINT uq_gs_authored_world_source_intake_source_request
        UNIQUE (target_namespace, source_registration_request_id),
    CONSTRAINT uq_gs_authored_world_source_intake_source_operation
        UNIQUE (target_namespace, source_operation_id),
    CONSTRAINT uq_gs_authored_world_source_intake_selector
        UNIQUE (target_namespace, canonical_tenant_id, world_slug),
    CONSTRAINT fk_gs_authored_world_source_tenant_binding
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
    CONSTRAINT chk_gs_authored_world_source_intake_schema
        CHECK (schema_version = 1 AND source_schema_version = 1),
    CONSTRAINT chk_gs_authored_world_source_intake_ids
        CHECK (
            operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND intake_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND source_registration_request_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND source_operation_id <> '00000000-0000-0000-0000-000000000000'::uuid
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::uuid
        ),
    CONSTRAINT chk_gs_authored_world_source_intake_namespace
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT chk_gs_authored_world_source_intake_digests
        CHECK (
            request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_request_digest ~ '^sha256:[0-9a-f]{64}$'
            AND source_evidence_digest ~ '^sha256:[0-9a-f]{64}$'
            AND receipt_digest ~ '^sha256:[0-9a-f]{64}$'
        ),
    CONSTRAINT chk_gs_authored_world_source_intake_tenant_slug
        CHECK (
            octet_length(tenant_slug) BETWEEN 1 AND 120
            AND tenant_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        ),
    CONSTRAINT chk_gs_authored_world_source_intake_world_slug
        CHECK (
            octet_length(world_slug) BETWEEN 1 AND 120
            AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        ),
    CONSTRAINT chk_gs_authored_world_source_intake_display_name
        CHECK (
            char_length(world_display_name) BETWEEN 1 AND 100
            AND world_display_name !~ '^[[:space:]]*$'
        ),
    CONSTRAINT chk_gs_authored_world_source_intake_source_row
        CHECK (source_game_row_id > 0),
    CONSTRAINT chk_gs_authored_world_source_intake_source_key
        CHECK (
            char_length(source_game_tenant_key) BETWEEN 1 AND 36
            AND source_game_tenant_key !~ '^[[:space:]]*$'
        ),
    CONSTRAINT chk_gs_authored_world_source_intake_provenance
        CHECK (source_provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V30'))
);

-- [jooq ignore start]
CREATE FUNCTION reject_game_session_authored_world_source_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Game Session authored-world source evidence is immutable';
END;
$$;

CREATE TRIGGER game_session_authored_world_tenant_binding_immutable
BEFORE UPDATE OR DELETE ON game_session_authored_world_tenant_source_binding
FOR EACH ROW EXECUTE FUNCTION reject_game_session_authored_world_source_mutation();

CREATE TRIGGER game_session_authored_world_source_intake_immutable
BEFORE UPDATE OR DELETE ON game_session_authored_world_source_intake
FOR EACH ROW EXECUTE FUNCTION reject_game_session_authored_world_source_mutation();

CREATE TRIGGER game_session_authored_world_tenant_binding_no_truncate
BEFORE TRUNCATE ON game_session_authored_world_tenant_source_binding
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_authored_world_source_mutation();

CREATE TRIGGER game_session_authored_world_source_intake_no_truncate
BEFORE TRUNCATE ON game_session_authored_world_source_intake
FOR EACH STATEMENT EXECUTE FUNCTION reject_game_session_authored_world_source_mutation();

CREATE FUNCTION require_game_session_authored_world_source_for_tenant_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM game_session_authored_world_source_intake source_intake
        WHERE source_intake.target_namespace = NEW.target_namespace
          AND source_intake.canonical_tenant_id = NEW.canonical_tenant_id
          AND source_intake.tenant_slug = NEW.tenant_slug
          AND source_intake.source_game_row_id = NEW.source_game_row_id
          AND source_intake.source_game_tenant_key = NEW.source_game_tenant_key
          AND source_intake.source_provenance_kind = NEW.provenance_kind
    ) THEN
        RAISE EXCEPTION 'Game Session tenant source binding cannot commit without a world source';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER game_session_authored_world_tenant_binding_requires_source
AFTER INSERT ON game_session_authored_world_tenant_source_binding
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_game_session_authored_world_source_for_tenant_binding();
-- [jooq ignore stop]
