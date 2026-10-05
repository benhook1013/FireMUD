CREATE TABLE game_tenant_creation_operations (
    operation_id UUID PRIMARY KEY,
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    target_namespace VARCHAR(63) NOT NULL,
    creation_request_id UUID NOT NULL,
    request_digest VARCHAR(71) NOT NULL,
    source_game_tenant_key VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    status VARCHAR(16) NOT NULL,
    canonical_tenant_id UUID,
    source_game_row_id BIGINT REFERENCES game(id),
    provenance_kind VARCHAR(32),
    evidence_digest VARCHAR(71),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_game_tenant_creation_request UNIQUE (target_namespace, creation_request_id),
    CONSTRAINT uq_game_tenant_creation_source_key UNIQUE (source_game_tenant_key),
    CONSTRAINT uq_game_tenant_creation_source_row UNIQUE (source_game_row_id),
    CONSTRAINT uq_game_tenant_creation_canonical_tenant UNIQUE (canonical_tenant_id),
    CONSTRAINT chk_game_tenant_creation_operation_id_non_nil
        CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_creation_request_id_non_nil
        CHECK (creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_creation_namespace_dns_label
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT chk_game_tenant_creation_request_digest_shape
        CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_game_tenant_creation_source_key_length
        CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36),
    CONSTRAINT chk_game_tenant_creation_name_length
        CHECK (char_length(name) <= 100),
    CONSTRAINT chk_game_tenant_creation_description_bytes
        CHECK (
            description IS NULL
            OR (char_length(description) <= 255 AND octet_length(description) <= 1020)
        ),
    CONSTRAINT chk_game_tenant_creation_status
        CHECK (status IN ('PENDING', 'COMPLETED')),
    CONSTRAINT chk_game_tenant_creation_completion_shape CHECK (
        (status = 'PENDING'
            AND canonical_tenant_id IS NULL
            AND source_game_row_id IS NULL
            AND provenance_kind IS NULL
            AND evidence_digest IS NULL)
        OR
        (status = 'COMPLETED'
            AND canonical_tenant_id IS NOT NULL
            AND canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND source_game_row_id IS NOT NULL
            AND source_game_row_id > 0
            AND provenance_kind IS NOT NULL
            AND provenance_kind = 'NEW_GAME_ROW'
            AND evidence_digest IS NOT NULL
            AND evidence_digest ~ '^sha256:[0-9a-f]{64}$')
    )
);

-- [jooq ignore start]
CREATE FUNCTION enforce_game_tenant_creation_operation() RETURNS trigger AS $$
DECLARE
    source_matches BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Game tenant creation operation evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PENDING'
            OR NEW.canonical_tenant_id IS NOT NULL
            OR NEW.source_game_row_id IS NOT NULL
            OR NEW.provenance_kind IS NOT NULL
            OR NEW.evidence_digest IS NOT NULL THEN
            RAISE EXCEPTION 'Game tenant creation operations must begin pending'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status <> 'PENDING' OR NEW.status <> 'COMPLETED'
        OR NEW.operation_id IS DISTINCT FROM OLD.operation_id
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
        OR NEW.target_namespace IS DISTINCT FROM OLD.target_namespace
        OR NEW.creation_request_id IS DISTINCT FROM OLD.creation_request_id
        OR NEW.request_digest IS DISTINCT FROM OLD.request_digest
        OR NEW.source_game_tenant_key IS DISTINCT FROM OLD.source_game_tenant_key
        OR NEW.name IS DISTINCT FROM OLD.name
        OR NEW.description IS DISTINCT FROM OLD.description
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR OLD.canonical_tenant_id IS NOT NULL
        OR OLD.source_game_row_id IS NOT NULL
        OR OLD.provenance_kind IS NOT NULL
        OR OLD.evidence_digest IS NOT NULL THEN
        RAISE EXCEPTION 'Game tenant creation operation evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM game g
        WHERE g.id = NEW.source_game_row_id
          AND g.tenant_id = NEW.source_game_tenant_key
          AND g.canonical_tenant_id = NEW.canonical_tenant_id
          AND g.tenant_identity_provenance_kind = 'NEW_GAME_ROW'
          AND g.tenant_identity_source_game_id = g.id
          AND g.tenant_identity_source_legacy_tenant_id = g.tenant_id
    ) INTO source_matches;

    IF NOT source_matches THEN
        RAISE EXCEPTION 'Game tenant creation source evidence does not match a new game row'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_operation_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON game_tenant_creation_operations
    FOR EACH ROW EXECUTE FUNCTION enforce_game_tenant_creation_operation();

CREATE FUNCTION reject_game_tenant_creation_operation_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game tenant creation operation evidence cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_operation_no_truncate
    BEFORE TRUNCATE ON game_tenant_creation_operations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_tenant_creation_operation_truncate();

CREATE FUNCTION require_completed_game_tenant_creation_operation() RETURNS trigger AS $$
DECLARE
    current_status VARCHAR(16);
BEGIN
    SELECT status INTO current_status
    FROM game_tenant_creation_operations
    WHERE operation_id = NEW.operation_id;

    IF current_status IS DISTINCT FROM 'COMPLETED' THEN
        RAISE EXCEPTION 'Game tenant creation operation cannot commit while pending'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER game_tenant_creation_operation_must_complete
    AFTER INSERT OR UPDATE ON game_tenant_creation_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_completed_game_tenant_creation_operation();
-- [jooq ignore stop]

