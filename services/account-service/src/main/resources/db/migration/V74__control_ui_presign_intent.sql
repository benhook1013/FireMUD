-- Supplemental signing identity for the SAME V66 operation, never a second operation owner.
-- These sequences allocate durable capture metadata, not authority generations or checkpoints.
CREATE SEQUENCE account_control_ui_capture_source_version_seq AS BIGINT MINVALUE 1 NO CYCLE;
CREATE SEQUENCE account_control_ui_capture_source_fence_seq AS BIGINT MINVALUE 1 NO CYCLE;
CREATE TABLE account_control_ui_issuance_intents (
    operation_id UUID PRIMARY KEY REFERENCES account_control_ui_issuance_operations(operation_id) ON DELETE RESTRICT,
    jti UUID NOT NULL UNIQUE CHECK (jti <> '00000000-0000-0000-0000-000000000000'),
    bundle_id UUID NOT NULL UNIQUE CHECK (bundle_id <> '00000000-0000-0000-0000-000000000000'),
    bundle_version BIGINT NOT NULL CHECK (bundle_version = 1),
    source_version BIGINT NOT NULL UNIQUE DEFAULT nextval('account_control_ui_capture_source_version_seq') CHECK (source_version > 0),
    source_fence BIGINT NOT NULL UNIQUE DEFAULT nextval('account_control_ui_capture_source_fence_seq') CHECK (source_fence > 0),
    authority_capture_digest BYTEA NOT NULL CHECK (octet_length(authority_capture_digest) = 32),
    issuance_fence_digest BYTEA NOT NULL CHECK (octet_length(issuance_fence_digest) = 32),
    phase VARCHAR(16) NOT NULL CHECK (phase IN ('PENDING', 'HASH_BOUND')),
    token_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_control_ui_intent_hash_shape CHECK (
        (phase = 'PENDING' AND token_hash IS NULL) OR
        (phase = 'HASH_BOUND' AND token_hash ~ '^[0-9a-f]{64}$' AND token_hash IS NOT NULL))
);

-- [jooq ignore start]
CREATE FUNCTION account_control_ui_intent_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE operation account_control_ui_issuance_operations%ROWTYPE;
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT * INTO operation FROM account_control_ui_issuance_operations
            WHERE operation_id = NEW.operation_id FOR UPDATE;
        IF NOT FOUND OR operation.status <> 'PENDING' OR NEW.phase <> 'PENDING'
            OR NEW.token_hash IS NOT NULL
            OR NEW.authority_capture_digest IS DISTINCT FROM operation.authority_capture_digest
            OR NEW.issuance_fence_digest IS DISTINCT FROM operation.issuance_fence_digest THEN
            RAISE EXCEPTION 'Control-UI intent requires the exact pending original operation capture'
                USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_intent_operation_binding';
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.phase <> 'PENDING' OR NEW.phase <> 'HASH_BOUND' OR NEW.token_hash IS NULL
            OR (to_jsonb(NEW) - ARRAY['phase','token_hash']) IS DISTINCT FROM
               (to_jsonb(OLD) - ARRAY['phase','token_hash']) THEN
            RAISE EXCEPTION 'Control-UI signing identity and original capture are immutable'
                USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_intent_identity_immutable';
        END IF;
    ELSE
        RAISE EXCEPTION 'Control-UI signing evidence is retained'
            USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_intent_retained';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_control_ui_intent_guard_trigger
    BEFORE INSERT OR UPDATE OR DELETE ON account_control_ui_issuance_intents
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_intent_guard();
CREATE TRIGGER account_control_ui_intent_no_truncate_trigger
    BEFORE TRUNCATE ON account_control_ui_issuance_intents
    FOR EACH STATEMENT EXECUTE FUNCTION account_control_ui_intent_guard();
-- Existing V66-only fixtures/retained operations remain unchanged. Once a reservation exists,
-- the final original response must use its already-bound exact token hash.
CREATE FUNCTION account_control_ui_intent_result_guard() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'COMMITTED' AND EXISTS (
        SELECT 1 FROM account_control_ui_issuance_intents WHERE operation_id = NEW.operation_id
    ) AND NOT EXISTS (
        SELECT 1 FROM account_control_ui_issuance_intents
        WHERE operation_id = NEW.operation_id AND phase = 'HASH_BOUND' AND token_hash = NEW.token_hash
            AND authority_capture_digest = NEW.authority_capture_digest
            AND issuance_fence_digest = NEW.issuance_fence_digest
    ) THEN
        RAISE EXCEPTION 'Control-UI original response differs from its reserved signing identity'
            USING ERRCODE = '23514', CONSTRAINT = 'account_control_ui_intent_result_binding';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER account_control_ui_intent_result_guard_trigger
    AFTER UPDATE ON account_control_ui_issuance_operations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_intent_result_guard();
-- [jooq ignore stop]
