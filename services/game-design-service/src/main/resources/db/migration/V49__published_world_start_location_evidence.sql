-- Retained attestations keep their original schema and bytes; no selector is inferred or backfilled.
ALTER TABLE published_release_bundle
    ADD COLUMN world_published_start_location_evidence_json TEXT;

-- This adds an insertion guard beside (and does not replace) the existing immutable row/source guards.
-- Payload decoding checks storage relations, not authenticated World producer provenance.
-- [jooq ignore start]
-- The retained Account and World operation use big-endian byte-length frames.
CREATE FUNCTION published_world_selector_frame(payload BYTEA, wanted INTEGER) RETURNS BYTEA AS $$
DECLARE
    offset_bytes INTEGER := 0;
    frame_index INTEGER := 0;
    frame_length BIGINT;
BEGIN
    WHILE offset_bytes < octet_length(payload) LOOP
        IF offset_bytes + 4 > octet_length(payload) THEN
            RAISE EXCEPTION 'truncated published World evidence frame' USING ERRCODE = 'check_violation';
        END IF;
        frame_length := get_byte(payload, offset_bytes)::BIGINT * 16777216
            + get_byte(payload, offset_bytes + 1)::BIGINT * 65536
            + get_byte(payload, offset_bytes + 2)::BIGINT * 256 + get_byte(payload, offset_bytes + 3);
        offset_bytes := offset_bytes + 4;
        IF frame_length > octet_length(payload) - offset_bytes THEN
            RAISE EXCEPTION 'invalid published World evidence frame length' USING ERRCODE = 'check_violation';
        END IF;
        IF frame_index = wanted THEN
            RETURN substring(payload FROM offset_bytes + 1 FOR frame_length::INTEGER);
        END IF;
        offset_bytes := offset_bytes + frame_length::INTEGER;
        frame_index := frame_index + 1;
    END LOOP;
    RAISE EXCEPTION 'missing published World evidence frame' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql IMMUTABLE STRICT;

CREATE FUNCTION enforce_published_world_start_location_evidence() RETURNS trigger AS $$
DECLARE
    evidence JSONB;
    selection JSONB;
    applied JSONB;
    receipt JSONB;
    participants JSONB;
    item JSONB;
    owner_key TEXT;
    required_schema INTEGER;
    field_name TEXT;
    account_bytes BYTEA;
    operation_bytes BYTEA;
    draft JSONB;
    affected JSONB;
BEGIN
    IF NEW.attestation_schema_version <> 'v2' THEN
        IF NEW.world_published_start_location_evidence_json IS NOT NULL THEN
            RAISE EXCEPTION 'historical release schemas cannot carry a World selector'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.world_published_start_location_evidence_json IS NULL
        OR NEW.script_only OR NEW.script_patch_version IS NOT NULL
        OR NEW.canonical_tenant_id IS NULL OR NEW.canonical_version_id IS NULL
        OR NEW.manifest_schema_version IS NULL OR NEW.artifact_digests_json IS NULL THEN
        RAISE EXCEPTION 'release v2 requires complete full-Version selector evidence'
            USING ERRCODE = 'check_violation';
    END IF;
    evidence := NEW.world_published_start_location_evidence_json::JSONB;
    selection := evidence->'request';
    FOR field_name IN SELECT unnest(ARRAY['targetNamespace','canonicalTenantId','canonicalVersionId',
        'intakeRequestId','publicationFence','publicationRequestId','requestDigest',
        'publishWorkflowId','appliedCommitId','contentDigest'])
    LOOP
        IF jsonb_typeof(selection->field_name) IS DISTINCT FROM 'string'
            OR coalesce(selection->>field_name,'') = '' THEN
            RAISE EXCEPTION 'release v2 has missing selection text binding' USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    IF jsonb_typeof(evidence) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(evidence)) <> 5
        OR NOT evidence ?& ARRAY['schema','request','selectorReceiptBytesBase64',
            'originalAccountBindingBytesBase64','appliedResultBytesBase64']
        OR evidence->>'schema' IS DISTINCT FROM 'world-published-start-location-evidence/v1'
        OR jsonb_typeof(selection) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(selection)) <> 13
        OR NOT selection ?& ARRAY['targetNamespace','canonicalTenantId','canonicalVersionId',
            'intakeRequestId','publicationFence','publicationRequestId','requestDigest',
            'versionStateEpoch','publishWorkflowId','appliedCommitId','contentDigest',
            'digestSchemaVersion','worldAffectedTuples']
        OR selection->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR selection->>'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR selection->>'publishWorkflowId' IS DISTINCT FROM NEW.publish_workflow_id
        OR selection->>'targetNamespace' !~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
        OR selection->>'intakeRequestId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR (selection->>'intakeRequestId')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
        OR selection->>'publicationFence' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR (selection->>'publicationFence')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
        OR selection->>'appliedCommitId' !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        OR (selection->>'appliedCommitId')::UUID = '00000000-0000-0000-0000-000000000000'::UUID
        OR coalesce(selection->>'publicationRequestId','') = ''
        OR selection->>'requestDigest' !~ '^[0-9a-f]{64}$'
        OR selection->>'contentDigest' !~ '^[0-9a-f]{64}$'
        OR selection->>'versionStateEpoch' !~ '^[1-9][0-9]*$'
        OR jsonb_typeof(selection->'versionStateEpoch') IS DISTINCT FROM 'string'
        OR length(selection->>'versionStateEpoch') > 19
        OR (length(selection->>'versionStateEpoch') = 19
            AND selection->>'versionStateEpoch' > '9223372036854775807')
        OR jsonb_typeof(selection->'digestSchemaVersion') IS DISTINCT FROM 'number'
        OR selection->>'digestSchemaVersion' IS DISTINCT FROM '3'
        OR jsonb_typeof(selection->'worldAffectedTuples') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'release v2 World selection has missing, unsupported or mismatched bindings'
            USING ERRCODE = 'check_violation';
    END IF;
    IF jsonb_array_length(selection->'worldAffectedTuples') = 0 THEN
        RAISE EXCEPTION 'release v2 requires the complete World affected set' USING ERRCODE = 'check_violation';
    END IF;
    -- Binary Account and APPLIED bytes remain intact; Java independently checks their full relation.
    IF jsonb_typeof(evidence->'originalAccountBindingBytesBase64') IS DISTINCT FROM 'string'
        OR octet_length(decode(evidence->>'originalAccountBindingBytesBase64','base64')) = 0
        OR jsonb_typeof(evidence->'appliedResultBytesBase64') IS DISTINCT FROM 'string'
        OR jsonb_typeof(evidence->'selectorReceiptBytesBase64') IS DISTINCT FROM 'string' THEN
        RAISE EXCEPTION 'release v2 requires complete original owner bytes'
            USING ERRCODE = 'check_violation';
    END IF;
    applied := convert_from(decode(evidence->>'appliedResultBytesBase64','base64'),'UTF8')::JSONB;
    receipt := convert_from(decode(evidence->>'selectorReceiptBytesBase64','base64'),'UTF8')::JSONB;
    FOR field_name IN SELECT unnest(ARRAY['selectorReceiptBytesBase64',
        'originalAccountBindingBytesBase64','appliedResultBytesBase64'])
    LOOP
        IF replace(encode(decode(evidence->>field_name,'base64'),'base64'), E'\n','')
            IS DISTINCT FROM evidence->>field_name THEN
            RAISE EXCEPTION 'release v2 owner bytes are not canonical base64' USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    FOR field_name IN SELECT unnest(ARRAY['schema','status','operationBytesBase64','graphBytesBase64',
        'graphDigest','startLocationReceiptBase64','startLocationReceiptDigest'])
    LOOP
        IF jsonb_typeof(applied->field_name) IS DISTINCT FROM 'string'
            OR coalesce(applied->>field_name,'') = '' THEN
            RAISE EXCEPTION 'release v2 APPLIED result has missing bindings' USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    FOR field_name IN SELECT unnest(ARRAY['schema','targetNamespace','operationId','requestId','commitId',
        'authorizationFenceId','accountBindingDigest','bindingDigest','graphDigest','receiptDigest'])
    LOOP
        IF jsonb_typeof(receipt->field_name) IS DISTINCT FROM 'string'
            OR coalesce(receipt->>field_name,'') = '' THEN
            RAISE EXCEPTION 'release v2 selector receipt has missing bindings' USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    account_bytes := decode(evidence->>'originalAccountBindingBytesBase64','base64');
    operation_bytes := decode(applied->>'operationBytesBase64','base64');
    draft := convert_from(published_world_selector_frame(account_bytes, 11),'UTF8')::JSONB;
    SELECT coalesce(jsonb_agg(unit ORDER BY unit->>'owner', unit->>'aggregateType',
        unit->>'aggregateId', unit->>'scopeType', unit->>'scopeId', unit->>'expectedEpoch'),'[]'::JSONB)
        INTO affected FROM jsonb_array_elements(draft->'affectedUnits') unit
        WHERE unit->>'owner' = 'WORLD_MANAGEMENT';
    IF applied->>'schema' IS DISTINCT FROM 'world-draft-graph-applied/v2'
        OR applied->>'status' IS DISTINCT FROM 'APPLIED'
        OR applied->>'startLocationReceiptBase64' IS DISTINCT FROM evidence->>'selectorReceiptBytesBase64'
        OR receipt->>'schema' IS DISTINCT FROM 'world-draft-start-location-receipt/v1'
        OR receipt->>'targetNamespace' IS DISTINCT FROM selection->>'targetNamespace'
        OR receipt->>'commitId' IS DISTINCT FROM selection->>'appliedCommitId'
        OR receipt->'startLocation'->>'tenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR receipt->'startLocation'->>'versionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR receipt->>'graphDigest' IS DISTINCT FROM applied->>'graphDigest'
        OR receipt->>'receiptDigest' IS DISTINCT FROM applied->>'startLocationReceiptDigest'
        OR jsonb_typeof(applied) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(applied)) <> 8
        OR NOT applied ?& ARRAY['schema','status','operationBytesBase64','graphBytesBase64',
            'graphDigest','startLocationReceiptBase64','startLocationReceiptDigest','appliedEpochs']
        OR jsonb_typeof(receipt) IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(receipt)) <> 11
        OR NOT receipt ?& ARRAY['schema','targetNamespace','operationId','requestId','commitId',
            'authorizationFenceId','accountBindingDigest','bindingDigest','startLocation','graphDigest','receiptDigest']
        OR jsonb_typeof(receipt->'startLocation') IS DISTINCT FROM 'object'
        OR (SELECT count(*) FROM jsonb_object_keys(receipt->'startLocation')) <> 3
        OR NOT (receipt->'startLocation') ?& ARRAY['tenantId','versionId','roomTemplateId']
        OR selection->'worldAffectedTuples' IS DISTINCT FROM affected
        OR draft->>'canonicalTenantId' IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR draft->>'canonicalVersionId' IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR draft->>'commitId' IS DISTINCT FROM selection->>'appliedCommitId'
        OR draft->'target'->>'gameDesignVersionRowId' IS DISTINCT FROM NEW.version_id::TEXT
        OR draft->'target'->>'gameDesignVersionTenantKey' IS DISTINCT FROM NEW.tenant_id
        OR published_world_selector_frame(account_bytes, 11) IS DISTINCT FROM published_world_selector_frame(account_bytes, 12)
        OR published_world_selector_frame(operation_bytes, 7) IS DISTINCT FROM published_world_selector_frame(account_bytes, 11)
        OR convert_from(published_world_selector_frame(account_bytes, 13),'UTF8') IS DISTINCT FROM receipt->>'bindingDigest'
        OR receipt->>'accountBindingDigest' IS DISTINCT FROM 'sha256:' || encode(sha256(account_bytes),'hex')
        OR applied->>'graphDigest' IS DISTINCT FROM 'sha256:' || encode(sha256(decode(applied->>'graphBytesBase64','base64')),'hex')
        OR convert_from(published_world_selector_frame(account_bytes, 0),'UTF8') IS DISTINCT FROM 'account-draft-authorization-fence/v1'
        OR convert_from(published_world_selector_frame(account_bytes, 1),'UTF8') IS DISTINCT FROM receipt->>'operationId'
        OR convert_from(published_world_selector_frame(account_bytes, 2),'UTF8') IS DISTINCT FROM receipt->>'requestId'
        OR convert_from(published_world_selector_frame(account_bytes, 3),'UTF8') IS DISTINCT FROM selection->>'appliedCommitId'
        OR convert_from(published_world_selector_frame(account_bytes, 4),'UTF8') IS DISTINCT FROM receipt->>'authorizationFenceId'
        OR convert_from(published_world_selector_frame(account_bytes, 6),'UTF8') IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR convert_from(published_world_selector_frame(account_bytes, 7),'UTF8') IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR convert_from(published_world_selector_frame(operation_bytes, 0),'UTF8') IS DISTINCT FROM 'world-draft-terminal-operation/v1'
        OR published_world_selector_frame(operation_bytes, 20) IS DISTINCT FROM account_bytes
        OR convert_from(published_world_selector_frame(operation_bytes, 13),'UTF8') IS DISTINCT FROM selection->>'intakeRequestId'
        OR convert_from(published_world_selector_frame(operation_bytes, 8),'UTF8') IS DISTINCT FROM selection->>'targetNamespace'
        OR convert_from(published_world_selector_frame(operation_bytes, 9),'UTF8') IS DISTINCT FROM NEW.canonical_tenant_id::TEXT
        OR convert_from(published_world_selector_frame(operation_bytes, 10),'UTF8') IS DISTINCT FROM NEW.canonical_version_id::TEXT
        OR receipt->'startLocation'->>'roomTemplateId' IS NULL THEN
        RAISE EXCEPTION 'release v2 selector differs from original selected APPLIED receipt'
            USING ERRCODE = 'check_violation';
    END IF;
    participants := NEW.participant_digests_json::JSONB;
    IF jsonb_typeof(participants) IS DISTINCT FROM 'array' OR jsonb_array_length(participants) <> 5 THEN
        RAISE EXCEPTION 'release v2 requires all five participant owners' USING ERRCODE = 'check_violation';
    END IF;
    FOR owner_key, required_schema IN SELECT * FROM (VALUES
        ('WORLD_MANAGEMENT',3),('ENTITY_MANAGEMENT',2),('GAME_LOGIC',1),
        ('AUTOMATION_SCRIPTING',5),('GAME_DESIGN_CONTROL_PLANE',1)) owners(owner_key, required_schema)
    LOOP
        IF (SELECT count(*) FROM jsonb_array_elements(participants) p
                WHERE p->>'participantKey' = owner_key) <> 1 THEN
            RAISE EXCEPTION 'release v2 has missing or duplicate participant owner' USING ERRCODE = 'check_violation';
        END IF;
        SELECT p INTO item FROM jsonb_array_elements(participants) p WHERE p->>'participantKey' = owner_key;
        IF item->>'scopeValue' IS DISTINCT FROM NEW.version_id::TEXT
            OR item->>'baseVersionId' IS NOT NULL
            OR item->>'appliedCommitId' IS DISTINCT FROM selection->>'appliedCommitId'
            OR item->>'digestSchemaVersion' IS DISTINCT FROM required_schema::TEXT
            OR coalesce(item->>'contentDigest','') !~ '^[0-9a-f]{64}$'
            OR coalesce(item->>'errorCode','') <> ''
            OR (owner_key = 'WORLD_MANAGEMENT' AND item->>'contentDigest' IS DISTINCT FROM selection->>'contentDigest')
            OR (owner_key = 'GAME_LOGIC' AND coalesce(item->>'abilitySchemaDigest','') !~ '^sha256:[0-9a-f]{64}$') THEN
            RAISE EXCEPTION 'release v2 participant differs from selected commit/digest/schema'
                USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_published_world_start_location_evidence
    BEFORE INSERT ON published_release_bundle
    FOR EACH ROW EXECUTE FUNCTION enforce_published_world_start_location_evidence();
-- [jooq ignore stop]
