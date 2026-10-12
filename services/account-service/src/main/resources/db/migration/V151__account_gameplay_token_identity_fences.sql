-- Only the real COMMITTED issuance transaction establishes initial fences; no registry adoption.
-- Historical COMMITTED rows with no fence fail closed and require fresh LOGIN.
CREATE TABLE account_gameplay_token_identity_fences (
    operation_id UUID PRIMARY KEY REFERENCES account_gameplay_delegation_issuance_operations (operation_id) ON DELETE RESTRICT,
    account_uuid UUID NOT NULL REFERENCES accounts (account_uuid) ON DELETE RESTRICT,
    issuance_request_id UUID NOT NULL UNIQUE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    token_jti UUID NOT NULL UNIQUE,
    not_before_epoch_second BIGINT NOT NULL,
    token_generation BIGINT NOT NULL,
    issuance_fence BIGINT NOT NULL,
    token_identity_fence BIGINT NOT NULL DEFAULT 1,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    revocation_request_id UUID UNIQUE,
    revocation_digest VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_gameplay_token_identity_shape CHECK (
        token_hash ~ '^[0-9a-f]{64}$' AND not_before_epoch_second > 0
        AND token_generation = 1 AND issuance_fence > 0
        AND account_uuid::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND operation_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND issuance_request_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND token_jti::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
    CONSTRAINT account_gameplay_token_revocation_shape CHECK (
        (state = 'ACTIVE' AND token_identity_fence = 1
            AND revocation_request_id IS NULL AND revocation_digest IS NULL)
        OR (state IN ('PENDING', 'COMMITTED')
            AND token_identity_fence = CASE WHEN state = 'PENDING' THEN 2 ELSE 3 END
            AND revocation_request_id IS NOT NULL AND revocation_digest IS NOT NULL
            AND revocation_request_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
            AND revocation_digest ~ '^[0-9a-f]{64}$'))
);

-- Retain immutable identity and forbid reset/recreation. Triggers reinforce the repository's
-- account-row-first lock order; callers must acquire that Account lock before row mutations.
-- [jooq ignore start]
CREATE FUNCTION account_gameplay_token_identity_fence_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Account token fence retention cannot be bypassed by deletion' USING ERRCODE = '23514';
    END IF;
    PERFORM account_uuid FROM accounts WHERE account_uuid = NEW.account_uuid FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account token fence Account source absent' USING ERRCODE = '23514';
    END IF;
    PERFORM operation_id FROM account_gameplay_delegation_issuance_operations
        WHERE operation_id = NEW.operation_id AND account_uuid = NEW.account_uuid
            AND request_id = NEW.issuance_request_id AND token_hash = NEW.token_hash
            AND token_jti = NEW.token_jti AND not_before_epoch_second = NEW.not_before_epoch_second
            AND token_generation = NEW.token_generation AND issuance_fence = NEW.issuance_fence
            AND status = 'COMMITTED' FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account token fence requires exact COMMITTED issuance' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'ACTIVE' OR NEW.token_identity_fence <> 1
            OR NEW.revocation_request_id IS NOT NULL OR NEW.revocation_digest IS NOT NULL THEN
            RAISE EXCEPTION 'Account token fence must begin ACTIVE' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.operation_id, NEW.account_uuid, NEW.issuance_request_id, NEW.token_hash,
            NEW.token_jti, NEW.not_before_epoch_second, NEW.token_generation, NEW.issuance_fence, NEW.created_at)
        IS DISTINCT FROM ROW(OLD.operation_id, OLD.account_uuid, OLD.issuance_request_id, OLD.token_hash,
            OLD.token_jti, OLD.not_before_epoch_second, OLD.token_generation, OLD.issuance_fence, OLD.created_at) THEN
        RAISE EXCEPTION 'Account token fence identity is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN RETURN NEW; END IF;
    IF OLD.state = 'ACTIVE' AND NEW.state = 'PENDING' AND NEW.token_identity_fence = 2 THEN
        RETURN NEW;
    END IF;
    -- Storage only: there is no completion API until the real Gateway deny/clear owner exists.
    IF OLD.state = 'PENDING' AND NEW.state = 'COMMITTED' AND NEW.token_identity_fence = 3
        AND NEW.revocation_request_id = OLD.revocation_request_id
        AND NEW.revocation_digest = OLD.revocation_digest THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Account token fence transition or revocation identity conflicts' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER account_gameplay_token_identity_fence_guard
BEFORE INSERT OR UPDATE OR DELETE ON account_gameplay_token_identity_fences
FOR EACH ROW EXECUTE FUNCTION account_gameplay_token_identity_fence_guard();
-- [jooq ignore stop]
