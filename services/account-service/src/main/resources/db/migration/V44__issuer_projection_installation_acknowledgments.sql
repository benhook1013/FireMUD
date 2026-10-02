-- Durable Account acknowledgment of a Game Session issuer-projection installation.
-- [jooq ignore start]
CREATE FUNCTION account_issuer_install_ack_frame_text(value TEXT)
RETURNS BYTEA
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT convert_to(
        octet_length(convert_to(value, 'UTF8'))::TEXT || ':', 'UTF8')
        || convert_to(value, 'UTF8')
$$;

CREATE FUNCTION account_issuer_install_ack_frame_bytes(value BYTEA)
RETURNS BYTEA
LANGUAGE SQL
IMMUTABLE
STRICT
AS $$
    SELECT convert_to(octet_length(value)::TEXT || ':', 'UTF8') || value
$$;
-- [jooq ignore stop]

CREATE TABLE account_issuer_projection_installation_acknowledgments (
    acknowledgment_id UUID NOT NULL,
    capture_operation_id UUID NOT NULL,
    capture_request_id UUID NOT NULL,
    issuer_id VARCHAR(512) NOT NULL,
    caller_workload_identity VARCHAR(512) NOT NULL,
    projection_key VARCHAR(2048) NOT NULL,
    capture_request_digest_version INTEGER NOT NULL,
    capture_request_digest BYTEA NOT NULL,
    request_digest_version INTEGER NOT NULL,
    request_digest BYTEA NOT NULL,
    installed_projection_json BYTEA NOT NULL,
    installed_projection_sha256 BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_issuer_install_ack_pk
        PRIMARY KEY (acknowledgment_id),
    CONSTRAINT account_issuer_install_ack_capture_uq
        UNIQUE (capture_operation_id),
    CONSTRAINT account_issuer_install_ack_capture_fk
        FOREIGN KEY (capture_operation_id)
        REFERENCES account_issuer_projection_reconciliation_receipts (operation_id)
        ON DELETE RESTRICT,
    CONSTRAINT account_issuer_install_ack_binding_check CHECK (
        acknowledgment_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND capture_operation_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND capture_request_id <> '00000000-0000-0000-0000-000000000000'::UUID
        AND length(btrim(issuer_id)) BETWEEN 1 AND 512
        AND length(btrim(caller_workload_identity)) BETWEEN 1 AND 512
        AND projection_key = 'session:game:auth:issuer-generation:v1:' || issuer_id
        AND capture_request_digest_version = 1
        AND octet_length(capture_request_digest) = 32
        AND request_digest_version = 1
        AND octet_length(request_digest) = 32
        AND octet_length(installed_projection_json) BETWEEN 1 AND 65536
        AND octet_length(installed_projection_sha256) = 32
        AND installed_projection_sha256 = sha256(installed_projection_json)
    ),
    CONSTRAINT account_issuer_install_ack_digest_check CHECK (
        request_digest = sha256(
            account_issuer_install_ack_frame_text(
                'issuer-projection-installation-ack/v1')
            || account_issuer_install_ack_frame_text(
                'ISSUER_PROJECTION_INSTALLATION_ACK')
            || account_issuer_install_ack_frame_text(issuer_id)
            || account_issuer_install_ack_frame_text(caller_workload_identity)
            || account_issuer_install_ack_frame_text(projection_key)
            || account_issuer_install_ack_frame_text(capture_operation_id::TEXT)
            || account_issuer_install_ack_frame_text(capture_request_id::TEXT)
            || account_issuer_install_ack_frame_text(
                capture_request_digest_version::TEXT)
            || account_issuer_install_ack_frame_text(
                encode(capture_request_digest, 'hex'))
            || account_issuer_install_ack_frame_bytes(installed_projection_json)
        )
    )
);

-- [jooq ignore start]
CREATE FUNCTION account_issuer_install_ack_capture_binding_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    capture_row account_issuer_projection_reconciliation_receipts%ROWTYPE;
BEGIN
    SELECT *
        INTO capture_row
        FROM account_issuer_projection_reconciliation_receipts
        WHERE operation_id = NEW.capture_operation_id;
    IF NOT FOUND
        OR capture_row.request_id IS DISTINCT FROM NEW.capture_request_id
        OR capture_row.issuer_id IS DISTINCT FROM NEW.issuer_id
        OR capture_row.caller_workload_identity IS DISTINCT FROM NEW.caller_workload_identity
        OR capture_row.projection_key IS DISTINCT FROM NEW.projection_key
        OR capture_row.request_digest_version IS DISTINCT FROM NEW.capture_request_digest_version
        OR capture_row.request_digest IS DISTINCT FROM NEW.capture_request_digest THEN
        RAISE EXCEPTION 'Issuer projection acknowledgment differs from its exact capture binding'
            USING ERRCODE = '23514',
                CONSTRAINT = 'account_issuer_install_ack_capture_binding';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_issuer_install_ack_capture_binding
    BEFORE INSERT ON account_issuer_projection_installation_acknowledgments
    FOR EACH ROW EXECUTE FUNCTION account_issuer_install_ack_capture_binding_guard();

CREATE FUNCTION account_issuer_install_ack_immutable_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Issuer projection installation acknowledgments are immutable'
        USING ERRCODE = '23514',
            CONSTRAINT = 'account_issuer_install_ack_immutable';
    RETURN OLD;
END;
$$;

CREATE TRIGGER account_issuer_install_ack_immutable
    BEFORE UPDATE OR DELETE ON account_issuer_projection_installation_acknowledgments
    FOR EACH ROW EXECUTE FUNCTION account_issuer_install_ack_immutable_guard();
-- [jooq ignore stop]
