ALTER TABLE game_tenant_creation_operations
    ADD COLUMN creator_qualification_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE game_tenant_creation_creator_qualifications (
    operation_id UUID PRIMARY KEY
        REFERENCES game_tenant_creation_operations(operation_id),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    initiating_account_id UUID NOT NULL,
    account_authorization_operation_id UUID NOT NULL,
    account_authorization_digest VARCHAR(71) NOT NULL,
    evidence_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_game_tenant_creator_initiator_non_nil
        CHECK (initiating_account_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_creator_authorization_operation_non_nil
        CHECK (
            account_authorization_operation_id
                <> '00000000-0000-0000-0000-000000000000'::UUID
        ),
    CONSTRAINT chk_game_tenant_creator_authorization_digest
        CHECK (account_authorization_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_game_tenant_creator_evidence_digest
        CHECK (evidence_digest ~ '^sha256:[0-9a-f]{64}$')
);

-- [jooq ignore start]
CREATE OR REPLACE FUNCTION enforce_game_tenant_creation_operation() RETURNS trigger AS $$
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
        OR NEW.creator_qualification_required IS DISTINCT FROM OLD.creator_qualification_required
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

CREATE FUNCTION enforce_game_tenant_creation_creator_qualification() RETURNS trigger AS $$
DECLARE
    owner_requires_qualification BOOLEAN;
    owner_status VARCHAR(16);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Game tenant creator qualification evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'Game tenant creator qualification evidence is immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT creator_qualification_required, status
      INTO owner_requires_qualification, owner_status
      FROM game_tenant_creation_operations
     WHERE operation_id = NEW.operation_id;

    IF owner_requires_qualification IS DISTINCT FROM TRUE OR owner_status <> 'COMPLETED' THEN
        RAISE EXCEPTION 'Creator qualification cannot attach to source-only or incomplete creation'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_creator_qualification_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON game_tenant_creation_creator_qualifications
    FOR EACH ROW EXECUTE FUNCTION enforce_game_tenant_creation_creator_qualification();

CREATE FUNCTION reject_game_tenant_creation_creator_qualification_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Game tenant creator qualification evidence cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_creator_qualification_no_truncate
    BEFORE TRUNCATE ON game_tenant_creation_creator_qualifications
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_tenant_creation_creator_qualification_truncate();

CREATE FUNCTION require_game_tenant_creation_creator_qualification() RETURNS trigger AS $$
DECLARE
    current_required BOOLEAN;
    current_status VARCHAR(16);
    qualification_exists BOOLEAN;
BEGIN
    SELECT creator_qualification_required, status
      INTO current_required, current_status
      FROM game_tenant_creation_operations
     WHERE operation_id = NEW.operation_id;

    IF current_status IS DISTINCT FROM 'COMPLETED' THEN
        RAISE EXCEPTION 'Game tenant creation operation cannot commit while pending'
            USING ERRCODE = 'check_violation';
    END IF;

    IF current_required THEN
        SELECT EXISTS (
            SELECT 1
              FROM game_tenant_creation_creator_qualifications q
             WHERE q.operation_id = NEW.operation_id
        ) INTO qualification_exists;
        IF NOT qualification_exists THEN
            RAISE EXCEPTION 'Qualified game tenant creation cannot commit without creator evidence'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER game_tenant_creation_creator_qualification_must_complete
    AFTER INSERT OR UPDATE ON game_tenant_creation_operations
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_game_tenant_creation_creator_qualification();
-- [jooq ignore stop]
