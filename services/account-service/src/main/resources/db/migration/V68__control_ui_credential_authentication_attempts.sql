-- Credential evidence only: the V66 row remains the sole issuance operation.
-- No password, OTP, credential hash, token, authority capture or completion is copied here.
CREATE TABLE account_control_ui_credential_authentication_attempts (
    attempt_id UUID PRIMARY KEY CHECK (attempt_id <> '00000000-0000-0000-0000-000000000000'),
    operation_id UUID NOT NULL REFERENCES account_control_ui_issuance_operations(operation_id),
    request_id UUID NOT NULL,
    account_uuid UUID NOT NULL,
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    account_provenance VARCHAR(40) NOT NULL,
    purpose VARCHAR(32) NOT NULL CHECK (purpose IN ('INITIAL_ISSUANCE', 'EXACT_RESPONSE_RECOVERY')),
    authentication_method VARCHAR(16) NOT NULL CHECK (authentication_method IN ('PASSWORD', 'EMAIL_OTP')),
    consumed_challenge_id BIGINT UNIQUE,
    authenticated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((authentication_method = 'PASSWORD' AND consumed_challenge_id IS NULL)
        OR (authentication_method = 'EMAIL_OTP' AND consumed_challenge_id IS NOT NULL AND consumed_challenge_id > 0))
);

-- [jooq ignore start]
CREATE FUNCTION account_control_ui_credential_attempt_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    operation account_control_ui_issuance_operations%ROWTYPE;
BEGIN
    EXECUTE format('SELECT * FROM %I.account_control_ui_issuance_operations WHERE operation_id = $1 FOR UPDATE', TG_TABLE_SCHEMA)
        INTO operation USING NEW.operation_id;
    IF operation.operation_id IS NULL
        OR operation.request_id IS DISTINCT FROM NEW.request_id
        OR operation.account_uuid IS DISTINCT FROM NEW.account_uuid
        OR operation.account_id IS DISTINCT FROM NEW.account_id
        OR operation.account_provenance IS DISTINCT FROM NEW.account_provenance
        OR operation.profile <> 'control-ui' OR operation.audience <> 'control-ui'
        OR (NEW.purpose = 'INITIAL_ISSUANCE' AND operation.status <> 'PENDING')
        OR (NEW.purpose = 'EXACT_RESPONSE_RECOVERY'
            AND (operation.status <> 'COMMITTED' OR operation.expires_at <= CURRENT_TIMESTAMP)) THEN
        RAISE EXCEPTION 'Control-UI credential attempt must bind its exact original operation'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION account_control_ui_credential_attempt_immutable_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Control-UI credential attempt evidence is immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_control_ui_credential_attempt_insert
    BEFORE INSERT ON account_control_ui_credential_authentication_attempts
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_credential_attempt_insert_guard();
CREATE TRIGGER account_control_ui_credential_attempt_immutable
    BEFORE UPDATE OR DELETE ON account_control_ui_credential_authentication_attempts
    FOR EACH ROW EXECUTE FUNCTION account_control_ui_credential_attempt_immutable_guard();
CREATE TRIGGER account_control_ui_credential_attempt_no_truncate
    BEFORE TRUNCATE ON account_control_ui_credential_authentication_attempts
    FOR EACH STATEMENT EXECUTE FUNCTION account_control_ui_credential_attempt_immutable_guard();
-- [jooq ignore stop]
