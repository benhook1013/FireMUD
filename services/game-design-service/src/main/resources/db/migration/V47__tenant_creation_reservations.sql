CREATE TABLE game_tenant_creation_reservations (
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    target_namespace VARCHAR(63) NOT NULL,
    creation_request_id UUID NOT NULL,
    request_digest VARCHAR(71) NOT NULL,
    operation_id UUID NOT NULL,
    canonical_tenant_id UUID NOT NULL,
    source_game_tenant_key VARCHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_game_tenant_creation_reservations
        PRIMARY KEY (target_namespace, creation_request_id),
    CONSTRAINT uq_game_tenant_creation_reservation_operation UNIQUE (operation_id),
    CONSTRAINT uq_game_tenant_creation_reservation_canonical_tenant UNIQUE (canonical_tenant_id),
    CONSTRAINT uq_game_tenant_creation_reservation_source_key UNIQUE (source_game_tenant_key),
    CONSTRAINT chk_game_tenant_reservation_request_non_nil
        CHECK (creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_reservation_operation_non_nil
        CHECK (operation_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_reservation_tenant_non_nil
        CHECK (canonical_tenant_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_reservation_namespace_dns_label
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT chk_game_tenant_reservation_request_digest
        CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_game_tenant_reservation_source_key_length
        CHECK (char_length(source_game_tenant_key) BETWEEN 1 AND 36),
    CONSTRAINT chk_game_tenant_reservation_name_length
        CHECK (char_length(name) <= 100),
    CONSTRAINT chk_game_tenant_reservation_description_length
        CHECK (
            description IS NULL
            OR (char_length(description) <= 255 AND octet_length(description) <= 1020)
        )
);

-- [jooq ignore start]
CREATE FUNCTION enforce_game_tenant_creation_reservation_immutable() RETURNS trigger AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Game tenant creation reservations are immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_reservation_immutable
    BEFORE UPDATE OR DELETE ON game_tenant_creation_reservations
    FOR EACH ROW EXECUTE FUNCTION enforce_game_tenant_creation_reservation_immutable();

CREATE FUNCTION reject_game_tenant_creation_reservation_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game tenant creation reservations cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_reservation_no_truncate
    BEFORE TRUNCATE ON game_tenant_creation_reservations
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_tenant_creation_reservation_truncate();
-- [jooq ignore stop]
