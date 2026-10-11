CREATE TABLE game_design_tenant_slug_binding (
    target_namespace VARCHAR(63) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    tenant_slug VARCHAR(120) NOT NULL,
    source_game_row_id BIGINT NOT NULL REFERENCES game(id),
    source_game_tenant_key VARCHAR(36) NOT NULL,
    provenance_kind VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_gd_tenant_slug_binding PRIMARY KEY (target_namespace, canonical_tenant_id),
    CONSTRAINT uq_gd_tenant_slug_binding_slug UNIQUE (target_namespace, tenant_slug),
    CONSTRAINT uq_gd_tenant_slug_binding_row UNIQUE (target_namespace, source_game_row_id),
    CONSTRAINT uq_gd_tenant_slug_binding_source UNIQUE (
        target_namespace,
        canonical_tenant_id,
        tenant_slug,
        source_game_row_id,
        source_game_tenant_key,
        provenance_kind
    ),
    CONSTRAINT chk_gd_tenant_slug_binding_namespace CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
    ),
    CONSTRAINT chk_gd_tenant_slug_binding_tenant CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gd_tenant_slug_binding_slug CHECK (
        octet_length(tenant_slug) BETWEEN 1 AND 120
        AND tenant_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT chk_gd_tenant_slug_binding_source_key CHECK (
        char_length(source_game_tenant_key) BETWEEN 1 AND 36
        AND source_game_tenant_key !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gd_tenant_slug_binding_provenance CHECK (
        provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')
    )
);

CREATE TABLE game_design_authored_world_source_operations (
    operation_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    target_namespace VARCHAR(63) NOT NULL,
    registration_request_id UUID NOT NULL,
    request_digest VARCHAR(71) NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    tenant_slug VARCHAR(120) NOT NULL,
    world_slug VARCHAR(120) NOT NULL,
    world_display_name VARCHAR(400) NOT NULL,
    source_game_row_id BIGINT NOT NULL REFERENCES game(id),
    source_game_tenant_key VARCHAR(36) NOT NULL,
    provenance_kind VARCHAR(32) NOT NULL,
    evidence_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_gd_authored_world_request UNIQUE (target_namespace, registration_request_id),
    CONSTRAINT uq_gd_authored_world_selector UNIQUE (
        target_namespace,
        canonical_tenant_id,
        world_slug
    ),
    CONSTRAINT fk_gd_authored_world_tenant_binding FOREIGN KEY (
        target_namespace,
        canonical_tenant_id,
        tenant_slug,
        source_game_row_id,
        source_game_tenant_key,
        provenance_kind
    ) REFERENCES game_design_tenant_slug_binding (
        target_namespace,
        canonical_tenant_id,
        tenant_slug,
        source_game_row_id,
        source_game_tenant_key,
        provenance_kind
    ),
    CONSTRAINT chk_gd_authored_world_operation_id CHECK (
        operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gd_authored_world_request_id CHECK (
        registration_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gd_authored_world_namespace CHECK (
        target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
    ),
    CONSTRAINT chk_gd_authored_world_tenant_id CHECK (
        canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
    ),
    CONSTRAINT chk_gd_authored_world_digest_shape CHECK (
        request_digest ~ '^sha256:[0-9a-f]{64}$'
        AND evidence_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT chk_gd_authored_world_tenant_slug CHECK (
        octet_length(tenant_slug) BETWEEN 1 AND 120
        AND tenant_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT chk_gd_authored_world_world_slug CHECK (
        octet_length(world_slug) BETWEEN 1 AND 120
        AND world_slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'
    ),
    CONSTRAINT chk_gd_authored_world_display_name CHECK (
        char_length(world_display_name) BETWEEN 1 AND 100
        AND octet_length(world_display_name) <= 400
        AND world_display_name !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gd_authored_world_source_row CHECK (source_game_row_id > 0),
    CONSTRAINT chk_gd_authored_world_source_key CHECK (
        char_length(source_game_tenant_key) BETWEEN 1 AND 36
        AND source_game_tenant_key !~ '^[[:space:]]*$'
    ),
    CONSTRAINT chk_gd_authored_world_provenance CHECK (
        provenance_kind IN ('NEW_GAME_ROW', 'RETAINED_GAME_V29')
    )
);

-- [jooq ignore start]
CREATE FUNCTION enforce_gd_tenant_slug_binding() RETURNS trigger AS $$
DECLARE
    source_matches BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Game Design tenant selector binding is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM game g
        WHERE g.id = NEW.source_game_row_id
          AND g.tenant_id = NEW.source_game_tenant_key
          AND g.canonical_tenant_id = NEW.canonical_tenant_id
          AND g.tenant_identity_provenance_kind = NEW.provenance_kind
          AND g.tenant_identity_source_game_id = g.id
          AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
    ) INTO source_matches;

    IF NOT source_matches THEN
        RAISE EXCEPTION 'Game Design tenant selector source does not match its game row'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_tenant_slug_binding_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_tenant_slug_binding
    FOR EACH ROW EXECUTE FUNCTION enforce_gd_tenant_slug_binding();

CREATE FUNCTION enforce_gd_authored_world_source() RETURNS trigger AS $$
DECLARE
    source_matches BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Game Design authored-world source evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM game g
        JOIN game_design_tenant_slug_binding b
          ON b.target_namespace = NEW.target_namespace
         AND b.canonical_tenant_id = NEW.canonical_tenant_id
         AND b.tenant_slug = NEW.tenant_slug
         AND b.source_game_row_id = NEW.source_game_row_id
         AND b.source_game_tenant_key = NEW.source_game_tenant_key
         AND b.provenance_kind = NEW.provenance_kind
        WHERE g.id = NEW.source_game_row_id
          AND g.tenant_id = NEW.source_game_tenant_key
          AND g.canonical_tenant_id = NEW.canonical_tenant_id
          AND g.tenant_identity_provenance_kind = NEW.provenance_kind
          AND g.tenant_identity_source_game_id = g.id
          AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
    ) INTO source_matches;

    IF NOT source_matches THEN
        RAISE EXCEPTION 'Game Design authored-world source does not match its owner row'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_authored_world_source_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON game_design_authored_world_source_operations
    FOR EACH ROW EXECUTE FUNCTION enforce_gd_authored_world_source();

CREATE FUNCTION require_gd_tenant_slug_source() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM game_design_authored_world_source_operations o
        WHERE o.target_namespace = NEW.target_namespace
          AND o.canonical_tenant_id = NEW.canonical_tenant_id
          AND o.tenant_slug = NEW.tenant_slug
          AND o.source_game_row_id = NEW.source_game_row_id
          AND o.source_game_tenant_key = NEW.source_game_tenant_key
          AND o.provenance_kind = NEW.provenance_kind
    ) THEN
        RAISE EXCEPTION 'Game Design tenant selector claim cannot commit without a world source'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER gd_tenant_slug_binding_requires_source
    AFTER INSERT ON game_design_tenant_slug_binding
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_gd_tenant_slug_source();

CREATE FUNCTION reject_gd_authored_world_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game Design authored-world source evidence is immutable'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER gd_tenant_slug_binding_no_truncate
    BEFORE TRUNCATE ON game_design_tenant_slug_binding
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gd_authored_world_truncate();

CREATE TRIGGER gd_authored_world_source_no_truncate
    BEFORE TRUNCATE ON game_design_authored_world_source_operations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_gd_authored_world_truncate();
-- [jooq ignore stop]
