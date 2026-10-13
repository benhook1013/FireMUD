-- COMMITTED settlement must carry closed ACTIVE lifecycle evidence for the retained
-- PREPARING observation. Keep V1003's terminal, outcome, source and request guards installed.
-- This integrity guard does not authenticate World or duplicate its nested launch-binding codec:
-- launch binding and all immutable lifecycle values must remain exactly the retained owner values.
-- [jooq ignore start]
CREATE FUNCTION account_ss_activation_settlement_lifecycle_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    original account_start_session_activation_protections%ROWTYPE;
    result_value JSONB;
    lifecycle_base64 TEXT;
    lifecycle_bytes BYTEA;
    lifecycle JSONB;
    preparing JSONB;
    request_value JSONB;
    field_name TEXT;
    region RECORD;
    uuid_pattern CONSTANT TEXT := '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
    nil_uuid CONSTANT TEXT := '00000000-0000-0000-0000-000000000000';
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
    -- Check before JSONB normalizes duplicate keys, including duplicates in nested objects.
    IF NOT (convert_from(lifecycle_bytes, 'UTF8') IS JSON OBJECT WITH UNIQUE KEYS) THEN
        RAISE EXCEPTION 'Committed activation lifecycle requires unique closed JSON object keys'
            USING ERRCODE = '23514';
    END IF;
    -- JSON preserves the number spelling that JSONB may normalize (for example 1e0).
    IF (convert_from(lifecycle_bytes, 'UTF8')::JSON->'request'->'schemaVersion')::TEXT
        IS DISTINCT FROM '1' THEN
        RAISE EXCEPTION 'Committed activation lifecycle schemaVersion must be the integral JSON number 1'
            USING ERRCODE = '23514';
    END IF;
    lifecycle := convert_from(lifecycle_bytes, 'UTF8')::JSONB;
    preparing := convert_from(original.world_preparing_evidence, 'UTF8')::JSONB;
    IF jsonb_typeof(lifecycle) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Committed activation lifecycle must be a closed object'
            USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(lifecycle)) <> 12
        OR NOT (lifecycle ?& ARRAY[
            'schema', 'request', 'launchBindingBytesBase64', 'startLocation',
            'runtimeRoomInstanceId', 'lifecycleStatus', 'lifecycleEpoch', 'rowVersion',
            'captureId', 'graphSha256', 'preparationInputDigest', 'operationalRegionAssignments'])
        OR lifecycle->>'schema' IS DISTINCT FROM 'world-canonical-instance-lifecycle-evidence/v1'
        OR lifecycle->>'lifecycleStatus' IS DISTINCT FROM 'ACTIVE'
        OR preparing->>'lifecycleStatus' IS DISTINCT FROM 'PREPARING'
        OR jsonb_typeof(lifecycle->'request') IS DISTINCT FROM 'object'
        OR jsonb_typeof(lifecycle->'startLocation') IS DISTINCT FROM 'object'
        OR jsonb_typeof(lifecycle->'operationalRegionAssignments') IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'Committed activation lifecycle must be closed ACTIVE owner evidence'
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
    FOREACH field_name IN ARRAY ARRAY[
        'schema', 'launchBindingBytesBase64', 'runtimeRoomInstanceId', 'lifecycleStatus',
        'lifecycleEpoch', 'rowVersion', 'captureId', 'graphSha256', 'preparationInputDigest'] LOOP
        IF jsonb_typeof(lifecycle->field_name) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Committed activation lifecycle scalar must be a string: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    FOREACH field_name IN ARRAY ARRAY['runtimeRoomInstanceId', 'lifecycleEpoch', 'rowVersion'] LOOP
        IF lifecycle->>field_name !~ '^(0|[1-9][0-9]*)$'
            OR (lifecycle->>field_name)::NUMERIC > 9223372036854775807
            OR (field_name <> 'rowVersion' AND (lifecycle->>field_name)::NUMERIC = 0) THEN
            RAISE EXCEPTION 'Committed activation lifecycle counter is invalid: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF lifecycle->>'captureId' !~ uuid_pattern OR lifecycle->>'captureId' = nil_uuid
        OR lifecycle->>'graphSha256' !~ '^[0-9a-f]{64}$'
        OR lifecycle->>'preparationInputDigest' !~ '^sha256:[0-9a-f]{64}$'
        OR length(lifecycle->>'launchBindingBytesBase64') = 0
        OR regexp_replace(encode(decode(lifecycle->>'launchBindingBytesBase64', 'base64'),
            'base64'), E'[\n\r]', '', 'g')
            IS DISTINCT FROM lifecycle->>'launchBindingBytesBase64' THEN
        RAISE EXCEPTION 'Committed activation lifecycle identities or retained launch bytes are invalid'
            USING ERRCODE = '23514';
    END IF;
    request_value := lifecycle->'request';
    IF (SELECT count(*) FROM jsonb_object_keys(request_value)) <> 14
        OR NOT (request_value ?& ARRAY[
            'schemaVersion', 'readRequestId', 'targetNamespace', 'canonicalTenantId', 'worldSlug',
            'canonicalGameInstanceId', 'playableStateNamespaceId', 'playableStateScope',
            'publicProduction', 'controlPlaneRequestId', 'canonicalVersionId',
            'expectedDescriptorRequestDigest', 'expectedDescriptorResultDigest',
            'expectedReleaseAttestationDigest'])
        OR jsonb_typeof(request_value->'schemaVersion') IS DISTINCT FROM 'number'
        OR request_value->>'schemaVersion' IS DISTINCT FROM '1'
        OR request_value->'publicProduction' IS DISTINCT FROM 'true'::JSONB
        OR request_value->>'playableStateScope' IS DISTINCT FROM 'SHARED' THEN
        RAISE EXCEPTION 'Committed activation lifecycle request must have the complete closed shape'
            USING ERRCODE = '23514';
    END IF;
    FOREACH field_name IN ARRAY ARRAY[
        'readRequestId', 'targetNamespace', 'canonicalTenantId', 'worldSlug',
        'canonicalGameInstanceId', 'playableStateNamespaceId', 'playableStateScope',
        'controlPlaneRequestId', 'canonicalVersionId', 'expectedDescriptorRequestDigest',
        'expectedDescriptorResultDigest', 'expectedReleaseAttestationDigest'] LOOP
        IF jsonb_typeof(request_value->field_name) IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'Committed activation lifecycle request scalar must be a string: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    FOREACH field_name IN ARRAY ARRAY[
        'readRequestId', 'canonicalTenantId', 'canonicalGameInstanceId',
        'playableStateNamespaceId', 'canonicalVersionId'] LOOP
        IF request_value->>field_name !~ uuid_pattern OR request_value->>field_name = nil_uuid THEN
            RAISE EXCEPTION 'Committed activation lifecycle request UUID is invalid: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    FOREACH field_name IN ARRAY ARRAY[
        'expectedDescriptorRequestDigest', 'expectedDescriptorResultDigest',
        'expectedReleaseAttestationDigest'] LOOP
        IF request_value->>field_name !~ '^sha256:[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'Committed activation lifecycle request digest is invalid: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF length(request_value->>'targetNamespace') NOT BETWEEN 1 AND 63
        OR request_value->>'targetNamespace' !~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?$'
        OR octet_length(request_value->>'worldSlug') NOT BETWEEN 1 AND 120
        OR request_value->>'worldSlug' !~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        OR length(request_value->>'controlPlaneRequestId') NOT BETWEEN 1 AND 128
        OR request_value->>'controlPlaneRequestId' ~ '^[[:space:]]*$' THEN
        RAISE EXCEPTION 'Committed activation lifecycle request text is invalid'
            USING ERRCODE = '23514';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(lifecycle->'startLocation')) <> 3
        OR NOT (lifecycle->'startLocation' ?& ARRAY['tenantId', 'versionId', 'roomTemplateId']) THEN
        RAISE EXCEPTION 'Committed activation lifecycle start location must be closed'
            USING ERRCODE = '23514';
    END IF;
    FOREACH field_name IN ARRAY ARRAY['tenantId', 'versionId', 'roomTemplateId'] LOOP
        IF jsonb_typeof(lifecycle->'startLocation'->field_name) IS DISTINCT FROM 'string'
            OR lifecycle->'startLocation'->>field_name !~ uuid_pattern
            OR lifecycle->'startLocation'->>field_name = nil_uuid THEN
            RAISE EXCEPTION 'Committed activation lifecycle start location UUID is invalid: %', field_name
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM jsonb_object_keys(lifecycle->'operationalRegionAssignments')) = 0
        OR (SELECT count(*) FROM jsonb_each(lifecycle->'operationalRegionAssignments'))
            <> (SELECT count(DISTINCT value) FROM jsonb_each(lifecycle->'operationalRegionAssignments')) THEN
        RAISE EXCEPTION 'Committed activation lifecycle requires distinct operational region assignments'
            USING ERRCODE = '23514';
    END IF;
    FOR region IN SELECT key, value FROM jsonb_each(lifecycle->'operationalRegionAssignments') LOOP
        IF region.key !~ uuid_pattern OR region.key = nil_uuid
            OR jsonb_typeof(region.value) IS DISTINCT FROM 'string'
            OR region.value #>> '{}' !~ uuid_pattern OR region.value #>> '{}' = nil_uuid
            OR region.key = region.value #>> '{}' THEN
            RAISE EXCEPTION 'Committed activation lifecycle region assignment is invalid'
                USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
EXCEPTION WHEN invalid_text_representation OR character_not_in_repertoire OR numeric_value_out_of_range
    OR invalid_parameter_value THEN
    RAISE EXCEPTION 'Committed activation lifecycle encoding or scalar is invalid'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER account_ss_activation_settlement_validate_lifecycle
    BEFORE INSERT ON account_start_session_activation_protection_settlements
    FOR EACH ROW EXECUTE FUNCTION account_ss_activation_settlement_lifecycle_guard();
-- [jooq ignore end]
