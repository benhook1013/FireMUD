CREATE TABLE game_tenant_creation_authorized_dispatches (
    target_namespace VARCHAR(63) NOT NULL,
    creation_request_id UUID NOT NULL,
    account_authorization_operation_id UUID NOT NULL,
    initiating_account_id UUID NOT NULL,
    creation_request_digest VARCHAR(71) NOT NULL,
    account_authorization_digest VARCHAR(71) NOT NULL,
    authorization_read_request_id UUID NOT NULL,
    valid_before_epoch_millis BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (target_namespace, creation_request_id),
    CONSTRAINT uq_game_tenant_creation_authorization_read_id
        UNIQUE (authorization_read_request_id),
    CONSTRAINT fk_game_tenant_creation_authorized_dispatch_original
        FOREIGN KEY (target_namespace, creation_request_id)
        REFERENCES game_tenant_creation_original_outcomes(target_namespace, creation_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_game_tenant_creation_dispatch_creation_id_non_nil
        CHECK (creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_creation_dispatch_authorization_id_non_nil
        CHECK (
            account_authorization_operation_id
                <> '00000000-0000-0000-0000-000000000000'::UUID
        ),
    CONSTRAINT chk_game_tenant_creation_dispatch_account_id_non_nil
        CHECK (initiating_account_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_creation_dispatch_read_id_non_nil
        CHECK (authorization_read_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_creation_dispatch_namespace_dns_label
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT chk_game_tenant_creation_dispatch_creation_digest_shape
        CHECK (creation_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_game_tenant_creation_dispatch_authorization_digest_shape
        CHECK (account_authorization_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_game_tenant_creation_dispatch_valid_before
        CHECK (valid_before_epoch_millis > 0 AND valid_before_epoch_millis <= 253402300799999)
);

-- [jooq ignore start]
CREATE FUNCTION enforce_game_tenant_creation_authorized_dispatch() RETURNS trigger AS $$
DECLARE
    matching_original BOOLEAN;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Authorized fresh tenant dispatches are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    PERFORM lock_game_tenant_creation_identity(NEW.target_namespace, NEW.creation_request_id);

    SELECT EXISTS (
        SELECT 1
        FROM game_tenant_creation_original_outcomes original_outcome
        WHERE original_outcome.target_namespace = NEW.target_namespace
          AND original_outcome.creation_request_id = NEW.creation_request_id
          AND original_outcome.account_authorization_operation_id
                = NEW.account_authorization_operation_id
          AND original_outcome.initiating_account_id = NEW.initiating_account_id
          AND original_outcome.creation_request_digest = NEW.creation_request_digest
          AND original_outcome.account_authorization_digest = NEW.account_authorization_digest
          AND original_outcome.status = 'CREATED'
    ) INTO matching_original;

    IF NOT matching_original THEN
        RAISE EXCEPTION 'Authorized fresh tenant dispatch must match its exact created original'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_authorized_dispatch_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON game_tenant_creation_authorized_dispatches
    FOR EACH ROW EXECUTE FUNCTION enforce_game_tenant_creation_authorized_dispatch();

CREATE FUNCTION reject_game_tenant_creation_authorized_dispatch_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Authorized fresh tenant dispatches cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_authorized_dispatch_no_truncate
    BEFORE TRUNCATE ON game_tenant_creation_authorized_dispatches
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_tenant_creation_authorized_dispatch_truncate();

CREATE FUNCTION require_valid_game_tenant_creation_authorized_dispatch() RETURNS trigger AS $$
DECLARE
    original_record RECORD;
    operation_record RECORD;
BEGIN
    IF clock_timestamp() >= to_timestamp(NEW.valid_before_epoch_millis / 1000.0) THEN
        RAISE EXCEPTION 'Current Account tenant-creation authorization expired before Game Design commit'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO original_record
    FROM game_tenant_creation_original_outcomes original_outcome
    WHERE original_outcome.target_namespace = NEW.target_namespace
      AND original_outcome.creation_request_id = NEW.creation_request_id;

    IF NOT FOUND
        OR original_record.account_authorization_operation_id
            IS DISTINCT FROM NEW.account_authorization_operation_id
        OR original_record.initiating_account_id IS DISTINCT FROM NEW.initiating_account_id
        OR original_record.creation_request_digest IS DISTINCT FROM NEW.creation_request_digest
        OR original_record.account_authorization_digest
            IS DISTINCT FROM NEW.account_authorization_digest
        OR original_record.status IS DISTINCT FROM 'CREATED'
        OR original_record.creation_operation_id IS NULL
        OR original_record.creation_evidence_digest IS NULL THEN
        RAISE EXCEPTION 'Authorized fresh tenant dispatch does not match a terminal created original'
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT * INTO operation_record
    FROM game_tenant_creation_operations operation
    WHERE operation.operation_id = original_record.creation_operation_id
      AND operation.target_namespace = NEW.target_namespace
      AND operation.creation_request_id = NEW.creation_request_id
      AND operation.request_digest = NEW.creation_request_digest
      AND operation.evidence_digest = original_record.creation_evidence_digest
      AND operation.status = 'COMPLETED'
      AND operation.canonical_tenant_id IS NOT NULL
      AND operation.source_game_row_id > 0
      AND operation.provenance_kind = 'NEW_GAME_ROW'
      AND EXISTS (
          SELECT 1
          FROM game source_game
          WHERE source_game.id = operation.source_game_row_id
            AND source_game.tenant_id = operation.source_game_tenant_key
            AND source_game.canonical_tenant_id = operation.canonical_tenant_id
            AND source_game.tenant_identity_provenance_kind = 'NEW_GAME_ROW'
            AND source_game.tenant_identity_source_game_id = source_game.id
            AND source_game.tenant_identity_source_legacy_tenant_id = source_game.tenant_id
      );

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Authorized fresh tenant dispatch lacks its exact completed V30 receipt'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER game_tenant_creation_authorized_dispatch_must_complete
    AFTER INSERT ON game_tenant_creation_authorized_dispatches
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_valid_game_tenant_creation_authorized_dispatch();
-- [jooq ignore stop]
