-- [jooq ignore start]
CREATE FUNCTION lock_game_tenant_creation_identity(
    p_target_namespace TEXT,
    p_creation_request_id UUID
) RETURNS VOID AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended(
            'firemud.game-tenant-creation.original-outcome.v1:'
                || char_length(p_target_namespace)::TEXT
                || ':'
                || p_target_namespace
                || ':'
                || p_creation_request_id::TEXT,
            0
        )
    );
END;
$$ LANGUAGE plpgsql;
-- [jooq ignore stop]

CREATE TABLE game_tenant_creation_original_outcomes (
    target_namespace VARCHAR(63) NOT NULL,
    creation_request_id UUID NOT NULL,
    account_authorization_operation_id UUID NOT NULL,
    initiating_account_id UUID NOT NULL,
    creation_request_digest VARCHAR(71) NOT NULL,
    account_authorization_digest VARCHAR(71) NOT NULL,
    status VARCHAR(16) NOT NULL,
    creation_operation_id UUID REFERENCES game_tenant_creation_operations(operation_id),
    creation_evidence_digest VARCHAR(71),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (target_namespace, creation_request_id),
    CONSTRAINT uq_game_creation_original_account_operation
        UNIQUE (account_authorization_operation_id),
    CONSTRAINT chk_game_tenant_creation_original_request_id_non_nil
        CHECK (creation_request_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_creation_original_account_operation_id_non_nil
        CHECK (
            account_authorization_operation_id
                <> '00000000-0000-0000-0000-000000000000'::UUID
        ),
    CONSTRAINT chk_game_tenant_creation_original_account_id_non_nil
        CHECK (initiating_account_id <> '00000000-0000-0000-0000-000000000000'::UUID),
    CONSTRAINT chk_game_tenant_creation_original_namespace_dns_label
        CHECK (target_namespace ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'),
    CONSTRAINT chk_game_tenant_creation_original_request_digest_shape
        CHECK (creation_request_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_game_tenant_creation_original_account_digest_shape
        CHECK (account_authorization_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT chk_game_tenant_creation_original_status
        CHECK (status IN ('PENDING', 'CREATED', 'NOT_CREATED')),
    CONSTRAINT chk_game_tenant_creation_original_receipt_shape CHECK (
        (status IN ('PENDING', 'NOT_CREATED')
            AND creation_operation_id IS NULL
            AND creation_evidence_digest IS NULL)
        OR
        (status = 'CREATED'
            AND creation_operation_id IS NOT NULL
            AND creation_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
            AND creation_evidence_digest IS NOT NULL
            AND creation_evidence_digest ~ '^sha256:[0-9a-f]{64}$')
    )
);

-- [jooq ignore start]
CREATE FUNCTION enforce_game_tenant_creation_original_outcome() RETURNS trigger AS $$
DECLARE
    matching_creation BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM lock_game_tenant_creation_identity(OLD.target_namespace, OLD.creation_request_id);
        RAISE EXCEPTION 'Fresh tenant original outcomes are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        PERFORM lock_game_tenant_creation_identity(NEW.target_namespace, NEW.creation_request_id);

        IF NEW.status <> 'PENDING'
            OR NEW.creation_operation_id IS NOT NULL
            OR NEW.creation_evidence_digest IS NOT NULL THEN
            RAISE EXCEPTION 'Fresh tenant original outcomes must begin pending'
                USING ERRCODE = 'check_violation';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM game_tenant_creation_operations operation
            WHERE operation.target_namespace = NEW.target_namespace
              AND operation.creation_request_id = NEW.creation_request_id
        ) THEN
            RAISE EXCEPTION 'A retained fresh tenant creation cannot be adopted into a new original outcome'
                USING ERRCODE = 'check_violation';
        END IF;

        RETURN NEW;
    END IF;

    PERFORM lock_game_tenant_creation_identity(OLD.target_namespace, OLD.creation_request_id);

    IF NEW.target_namespace IS DISTINCT FROM OLD.target_namespace
        OR NEW.creation_request_id IS DISTINCT FROM OLD.creation_request_id
        OR NEW.account_authorization_operation_id
            IS DISTINCT FROM OLD.account_authorization_operation_id
        OR NEW.initiating_account_id IS DISTINCT FROM OLD.initiating_account_id
        OR NEW.creation_request_digest IS DISTINCT FROM OLD.creation_request_digest
        OR NEW.account_authorization_digest IS DISTINCT FROM OLD.account_authorization_digest
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR OLD.status <> 'PENDING'
        OR NEW.status NOT IN ('CREATED', 'NOT_CREATED')
        OR OLD.creation_operation_id IS NOT NULL
        OR OLD.creation_evidence_digest IS NOT NULL THEN
        RAISE EXCEPTION 'Fresh tenant original outcome binding and terminal result are immutable'
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.status = 'CREATED' THEN
        SELECT EXISTS (
            SELECT 1
            FROM game_tenant_creation_operations operation
            WHERE operation.operation_id = NEW.creation_operation_id
              AND operation.target_namespace = NEW.target_namespace
              AND operation.creation_request_id = NEW.creation_request_id
              AND operation.request_digest = NEW.creation_request_digest
              AND operation.evidence_digest = NEW.creation_evidence_digest
              AND operation.status = 'COMPLETED'
        ) INTO matching_creation;

        IF NOT matching_creation THEN
            RAISE EXCEPTION 'Created fresh tenant original outcome must match exact V30 evidence'
                USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        SELECT EXISTS (
            SELECT 1
            FROM game_tenant_creation_operations operation
            WHERE operation.target_namespace = NEW.target_namespace
              AND operation.creation_request_id = NEW.creation_request_id
        ) INTO matching_creation;

        IF matching_creation THEN
            RAISE EXCEPTION 'A fresh tenant creation cannot be closed as not created'
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_original_outcome_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON game_tenant_creation_original_outcomes
    FOR EACH ROW EXECUTE FUNCTION enforce_game_tenant_creation_original_outcome();

CREATE FUNCTION reject_game_tenant_creation_original_outcome_truncate() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Fresh tenant original outcomes cannot be truncated'
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_original_outcome_no_truncate
    BEFORE TRUNCATE ON game_tenant_creation_original_outcomes
    FOR EACH STATEMENT EXECUTE FUNCTION reject_game_tenant_creation_original_outcome_truncate();

CREATE FUNCTION require_terminal_game_tenant_creation_original_outcome() RETURNS trigger AS $$
DECLARE
    current_status VARCHAR(16);
BEGIN
    SELECT status INTO current_status
    FROM game_tenant_creation_original_outcomes
    WHERE target_namespace = NEW.target_namespace
      AND creation_request_id = NEW.creation_request_id;

    IF current_status IS DISTINCT FROM 'CREATED'
        AND current_status IS DISTINCT FROM 'NOT_CREATED' THEN
        RAISE EXCEPTION 'Fresh tenant original outcome cannot commit while pending'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER game_tenant_creation_original_outcome_must_complete
    AFTER INSERT OR UPDATE ON game_tenant_creation_original_outcomes
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_terminal_game_tenant_creation_original_outcome();

CREATE FUNCTION enforce_game_tenant_creation_original_outcome_on_create() RETURNS trigger AS $$
DECLARE
    bound_request_digest VARCHAR(71);
    original_status VARCHAR(16);
BEGIN
    PERFORM lock_game_tenant_creation_identity(NEW.target_namespace, NEW.creation_request_id);

    SELECT creation_request_digest, status
    INTO bound_request_digest, original_status
    FROM game_tenant_creation_original_outcomes
    WHERE target_namespace = NEW.target_namespace
      AND creation_request_id = NEW.creation_request_id;

    IF original_status = 'NOT_CREATED' THEN
        RAISE EXCEPTION 'A definitively closed fresh tenant original cannot be recreated'
            USING ERRCODE = 'check_violation';
    END IF;

    IF original_status IS NOT NULL
        AND bound_request_digest IS DISTINCT FROM NEW.request_digest THEN
        RAISE EXCEPTION 'Fresh tenant creation request digest does not match its original binding'
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER game_tenant_creation_operation_original_outcome_guard
    BEFORE INSERT ON game_tenant_creation_operations
    FOR EACH ROW EXECUTE FUNCTION enforce_game_tenant_creation_original_outcome_on_create();
-- [jooq ignore stop]
