-- Preserve V1004's closed ACTIVE lifecycle validation while requiring World-owned counters
-- to advance monotonically from the retained PREPARING evidence.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION account_ss_activation_settlement_lifecycle_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_start_session_activation_protections%ROWTYPE;
    result_value JSONB;
    lifecycle_base64 TEXT;
    lifecycle_bytes BYTEA;
    lifecycle JSONB;
    preparing JSONB;
BEGIN
    IF NEW.outcome <> 'COMMITTED' THEN
        RETURN NEW;
    END IF;
    SELECT * INTO STRICT original FROM account_start_session_activation_protections
        WHERE activation_protection_id = NEW.activation_protection_id;
    result_value := convert_from(decode(
        convert_from(NEW.terminal_bytes, 'UTF8')::JSONB->>'activationResultBytesBase64',
        'base64'), 'UTF8')::JSONB;
    IF jsonb_typeof(result_value->'lifecycleEvidenceBytesBase64') IS DISTINCT FROM 'string' THEN
        RAISE EXCEPTION 'Committed activation settlement requires ACTIVE lifecycle evidence'
            USING ERRCODE = '23514';
    END IF;
    lifecycle_base64 := result_value->>'lifecycleEvidenceBytesBase64';
    lifecycle_bytes := decode(lifecycle_base64, 'base64');
    IF regexp_replace(encode(lifecycle_bytes, 'base64'), E'[\n\r]', '', 'g')
        IS DISTINCT FROM lifecycle_base64 THEN
        RAISE EXCEPTION 'Committed activation lifecycle Base64 must be canonical'
            USING ERRCODE = '23514';
    END IF;
    lifecycle := account_ss_activation_validate_lifecycle(lifecycle_bytes, 'ACTIVE');
    preparing := convert_from(original.world_preparing_evidence, 'UTF8')::JSONB;
    IF preparing->>'lifecycleStatus' IS DISTINCT FROM 'PREPARING' THEN
        RAISE EXCEPTION 'Committed activation requires retained PREPARING owner evidence'
            USING ERRCODE = '23514';
    END IF;
    IF (lifecycle->>'lifecycleEpoch')::NUMERIC < (preparing->>'lifecycleEpoch')::NUMERIC
        OR (lifecycle->>'rowVersion')::NUMERIC < (preparing->>'rowVersion')::NUMERIC THEN
        RAISE EXCEPTION 'Committed activation lifecycle counters cannot regress'
            USING ERRCODE = '23514';
    END IF;
    -- Only status, counters and the read observation identity may change during activation.
    IF lifecycle - ARRAY['request', 'lifecycleStatus', 'lifecycleEpoch', 'rowVersion']
            IS DISTINCT FROM preparing - ARRAY['request', 'lifecycleStatus', 'lifecycleEpoch', 'rowVersion']
        OR (lifecycle->'request') - 'readRequestId'
            IS DISTINCT FROM (preparing->'request') - 'readRequestId' THEN
        RAISE EXCEPTION 'Committed activation lifecycle differs from retained PREPARING binding'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire OR numeric_value_out_of_range
    OR invalid_parameter_value THEN
    RAISE EXCEPTION 'Committed activation lifecycle encoding or scalar is invalid'
        USING ERRCODE = '23514';
END;
$$;
-- [jooq ignore end]
