-- Record the original Account fence separately from replay evidence and response ciphertext.
-- Existing receipts retain an absent capture without a data rewrite or backfill.
ALTER TABLE connect_token_issuance_operation
    ADD COLUMN issuance_fence_capture_schema TEXT,
    ADD COLUMN issuance_fence_capture_account_uuid UUID,
    ADD COLUMN issuance_fence_capture_value BIGINT,
    ADD COLUMN issuance_fence_capture_source_version BIGINT,
    ADD COLUMN issuance_fence_capture_digest BYTEA,
    ADD CONSTRAINT connect_token_issuance_fence_capture_shape CHECK (
        num_nonnulls(issuance_fence_capture_schema, issuance_fence_capture_account_uuid,
            issuance_fence_capture_value, issuance_fence_capture_source_version,
            issuance_fence_capture_digest) = 0
        OR (
            num_nonnulls(issuance_fence_capture_schema, issuance_fence_capture_account_uuid,
                issuance_fence_capture_value, issuance_fence_capture_source_version,
                issuance_fence_capture_digest) = 5
            AND issuance_fence_capture_schema = 'account-connect-issuance-fence/v1'
            AND issuance_fence_capture_account_uuid = account_uuid
            AND issuance_fence_capture_account_uuid <> '00000000-0000-0000-0000-000000000000'::UUID
            AND issuance_fence_capture_value > 0
            AND issuance_fence_capture_source_version > 0
            AND octet_length(issuance_fence_capture_digest) = 32));

-- [jooq ignore start]
CREATE FUNCTION connect_token_issuance_fence_capture_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF num_nonnulls(NEW.issuance_fence_capture_schema, NEW.issuance_fence_capture_account_uuid,
            NEW.issuance_fence_capture_value, NEW.issuance_fence_capture_source_version,
            NEW.issuance_fence_capture_digest) <> 0 THEN
            RAISE EXCEPTION 'Connect fence capture requires an existing pending operation'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF (NEW.issuance_fence_capture_schema, NEW.issuance_fence_capture_account_uuid,
        NEW.issuance_fence_capture_value, NEW.issuance_fence_capture_source_version,
        NEW.issuance_fence_capture_digest)
       IS NOT DISTINCT FROM
       (OLD.issuance_fence_capture_schema, OLD.issuance_fence_capture_account_uuid,
        OLD.issuance_fence_capture_value, OLD.issuance_fence_capture_source_version,
        OLD.issuance_fence_capture_digest) THEN
        RETURN NEW;
    END IF;
    IF OLD.issuance_fence_capture_schema IS NOT NULL THEN
        RAISE EXCEPTION 'Connect issuance fence capture is immutable' USING ERRCODE = '23514';
    END IF;
    IF OLD.status <> 'PENDING' OR NEW.status <> 'PENDING' THEN
        RAISE EXCEPTION 'Connect fence capture requires an existing pending operation'
            USING ERRCODE = '23514';
    END IF;
    IF NOT account_has_current_fence_provenance(NEW.account_uuid,
        NEW.issuance_fence_capture_value, NEW.issuance_fence_capture_source_version) THEN
        RAISE EXCEPTION 'Connect fence capture requires current Account provenance'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER connect_token_issuance_fence_capture_mutation
BEFORE INSERT OR UPDATE ON connect_token_issuance_operation
FOR EACH ROW EXECUTE FUNCTION connect_token_issuance_fence_capture_guard();
-- [jooq ignore stop]
