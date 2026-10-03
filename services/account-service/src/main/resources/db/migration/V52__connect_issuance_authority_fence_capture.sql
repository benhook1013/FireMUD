ALTER TABLE account_connect_token_issuance_operations
    ADD COLUMN issuance_fence_capture_schema VARCHAR(64),
    ADD COLUMN issuance_fence_capture_account_uuid UUID,
    ADD COLUMN issuance_fence_capture_value BIGINT,
    ADD COLUMN issuance_fence_capture_source_version BIGINT,
    ADD COLUMN issuance_fence_capture_digest BYTEA,
    ADD CONSTRAINT account_connect_token_issuance_fence_capture_check
        CHECK (
            (issuance_fence_capture_schema IS NULL
                AND issuance_fence_capture_account_uuid IS NULL
                AND issuance_fence_capture_value IS NULL
                AND issuance_fence_capture_source_version IS NULL
                AND issuance_fence_capture_digest IS NULL)
            OR
            (issuance_fence_capture_schema IS NOT NULL
                AND issuance_fence_capture_account_uuid IS NOT NULL
                AND issuance_fence_capture_value IS NOT NULL
                AND issuance_fence_capture_source_version IS NOT NULL
                AND issuance_fence_capture_digest IS NOT NULL
                AND issuance_fence_capture_schema = 'account-connect-issuance-fence/v1'
                AND issuance_fence_capture_account_uuid
                    <> '00000000-0000-0000-0000-000000000000'::UUID
                AND issuance_fence_capture_value > 0
                AND issuance_fence_capture_source_version > 0
                AND octet_length(issuance_fence_capture_digest) = 32)),
    ADD CONSTRAINT account_connect_token_issuance_fence_capture_binding_check
        CHECK (issuance_fence_capture_digest IS NULL
            OR (issuance_fence_digest IS NOT NULL
                AND issuance_fence_digest = issuance_fence_capture_digest));

-- [jooq ignore start]
CREATE FUNCTION account_connect_token_issuance_fence_capture_update_guard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (OLD.issuance_fence_capture_schema IS NOT NULL
            AND NEW.issuance_fence_capture_schema
                IS DISTINCT FROM OLD.issuance_fence_capture_schema)
        OR (OLD.issuance_fence_capture_account_uuid IS NOT NULL
            AND NEW.issuance_fence_capture_account_uuid
                IS DISTINCT FROM OLD.issuance_fence_capture_account_uuid)
        OR (OLD.issuance_fence_capture_value IS NOT NULL
            AND NEW.issuance_fence_capture_value IS DISTINCT FROM OLD.issuance_fence_capture_value)
        OR (OLD.issuance_fence_capture_source_version IS NOT NULL
            AND NEW.issuance_fence_capture_source_version
                IS DISTINCT FROM OLD.issuance_fence_capture_source_version)
        OR (OLD.issuance_fence_capture_digest IS NOT NULL
            AND NEW.issuance_fence_capture_digest IS DISTINCT FROM OLD.issuance_fence_capture_digest) THEN
        RAISE EXCEPTION 'Connect-token issuance fence capture cannot be replaced or removed'
            USING ERRCODE = '23514', CONSTRAINT = 'account_connect_token_issuance_fence_capture_immutable';
    END IF;
    IF OLD.issuance_fence_capture_schema IS NULL
        AND NEW.issuance_fence_capture_schema IS NOT NULL
        AND (OLD.status <> 'PENDING' OR NEW.status <> 'PENDING') THEN
        RAISE EXCEPTION 'Connect-token issuance fence capture requires a pending operation'
            USING ERRCODE = '23514', CONSTRAINT = 'account_connect_token_issuance_fence_capture_pending';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER account_connect_token_issuance_fence_capture_update_guard_trigger
    BEFORE UPDATE ON account_connect_token_issuance_operations
    FOR EACH ROW
    EXECUTE FUNCTION account_connect_token_issuance_fence_capture_update_guard();
-- [jooq ignore end]
