-- Add closure-qualified selected releases without reclassifying or rewriting retained v1/v2 rows.
-- New selected releases bind World digest schema 4; exact historical v2 evidence still parses.
-- [jooq ignore start]
CREATE OR REPLACE FUNCTION enforce_published_world_start_location_evidence() RETURNS trigger AS $$
DECLARE
    evidence JSONB;
    selection JSONB;
    applied JSONB;
    receipt JSONB;
    participants JSONB;
    item JSONB;
    owner_key TEXT;
    required_schema INTEGER;
    required_world_schema INTEGER;
    field_name TEXT;
    account_bytes BYTEA;
    operation_bytes BYTEA;
    draft JSONB;
    affected JSONB;
BEGIN
    IF NEW.attestation_schema_version NOT IN ('v2', 'v3') THEN
        IF NEW.world_published_start_location_evidence_json IS NOT NULL THEN
            RAISE EXCEPTION 'historical release schemas cannot carry a World selector'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    required_world_schema := CASE NEW.attestation_schema_version WHEN 'v2' THEN 3 ELSE 4 END;

    IF NEW.world_published_start_location_evidence_json IS NULL
        OR NEW.script_only OR NEW.script_patch_version IS NOT NULL
        OR NEW.canonical_tenant_id IS NULL OR NEW.canonical_version_id IS NULL
        OR NEW.manifest_schema_version IS NULL OR NEW.artifact_digests_json IS NULL THEN
        RAISE EXCEPTION 'selected release requires complete full-Version selector evidence'
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
            RAISE EXCEPTION 'selected release has missing selection text binding' USING ERRCODE = 'check_violation';
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
        OR selection->>'digestSchemaVersion' IS DISTINCT FROM required_world_schema::TEXT
        OR jsonb_typeof(selection->'worldAffectedTuples') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'selected World selection has missing, unsupported or mismatched bindings'
            USING ERRCODE = 'check_violation';
    END IF;
    IF jsonb_array_length(selection->'worldAffectedTuples') = 0 THEN
        RAISE EXCEPTION 'selected release requires the complete World affected set' USING ERRCODE = 'check_violation';
    END IF;
    -- Binary Account and APPLIED bytes remain intact; Java independently checks their full relation.
    IF jsonb_typeof(evidence->'originalAccountBindingBytesBase64') IS DISTINCT FROM 'string'
        OR octet_length(decode(evidence->>'originalAccountBindingBytesBase64','base64')) = 0
        OR jsonb_typeof(evidence->'appliedResultBytesBase64') IS DISTINCT FROM 'string'
        OR jsonb_typeof(evidence->'selectorReceiptBytesBase64') IS DISTINCT FROM 'string' THEN
        RAISE EXCEPTION 'selected release requires complete original owner bytes'
            USING ERRCODE = 'check_violation';
    END IF;
    applied := convert_from(decode(evidence->>'appliedResultBytesBase64','base64'),'UTF8')::JSONB;
    receipt := convert_from(decode(evidence->>'selectorReceiptBytesBase64','base64'),'UTF8')::JSONB;
    FOR field_name IN SELECT unnest(ARRAY['selectorReceiptBytesBase64',
        'originalAccountBindingBytesBase64','appliedResultBytesBase64'])
    LOOP
        IF replace(encode(decode(evidence->>field_name,'base64'),'base64'), E'\n','')
            IS DISTINCT FROM evidence->>field_name THEN
            RAISE EXCEPTION 'selected release owner bytes are not canonical base64' USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    FOR field_name IN SELECT unnest(ARRAY['schema','status','operationBytesBase64','graphBytesBase64',
        'graphDigest','startLocationReceiptBase64','startLocationReceiptDigest'])
    LOOP
        IF jsonb_typeof(applied->field_name) IS DISTINCT FROM 'string'
            OR coalesce(applied->>field_name,'') = '' THEN
            RAISE EXCEPTION 'selected APPLIED result has missing bindings' USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    FOR field_name IN SELECT unnest(ARRAY['schema','targetNamespace','operationId','requestId','commitId',
        'authorizationFenceId','accountBindingDigest','bindingDigest','graphDigest','receiptDigest'])
    LOOP
        IF jsonb_typeof(receipt->field_name) IS DISTINCT FROM 'string'
            OR coalesce(receipt->>field_name,'') = '' THEN
            RAISE EXCEPTION 'selected selector receipt has missing bindings' USING ERRCODE = 'check_violation';
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
        RAISE EXCEPTION 'selected selector differs from original APPLIED receipt'
            USING ERRCODE = 'check_violation';
    END IF;
    participants := NEW.participant_digests_json::JSONB;
    IF jsonb_typeof(participants) IS DISTINCT FROM 'array' OR jsonb_array_length(participants) <> 5 THEN
        RAISE EXCEPTION 'selected release requires all five participant owners' USING ERRCODE = 'check_violation';
    END IF;
    FOR owner_key, required_schema IN SELECT * FROM (VALUES
        ('WORLD_MANAGEMENT',required_world_schema),('ENTITY_MANAGEMENT',2),('GAME_LOGIC',1),
        ('AUTOMATION_SCRIPTING',5),('GAME_DESIGN_CONTROL_PLANE',2)) owners(owner_key, required_schema)
    LOOP
        IF (SELECT count(*) FROM jsonb_array_elements(participants) p
                WHERE p->>'participantKey' = owner_key) <> 1 THEN
            RAISE EXCEPTION 'selected release has missing or duplicate participant owner' USING ERRCODE = 'check_violation';
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
            RAISE EXCEPTION 'selected participant differs from commit/digest/schema'
                USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION guard_selected_publication_release() RETURNS trigger AS $$
DECLARE attempt publish_attempt%ROWTYPE; operation game_design_publication_operation%ROWTYPE;
BEGIN
    IF NEW.script_only THEN RETURN NEW; END IF;
    -- Old unselected history keeps its original read semantics; no operation is backfilled.
    IF NEW.attestation_schema_version NOT IN ('v2', 'v3') AND NOT EXISTS (SELECT 1 FROM game_design_authored_draft_publish_selection
        WHERE game_design_version_tenant_key = NEW.tenant_id AND game_design_version_row_id = NEW.version_id) THEN
        RETURN NEW;
    END IF;
    PERFORM 1 FROM game WHERE tenant_id = NEW.tenant_id FOR UPDATE;
    SELECT * INTO STRICT attempt FROM publish_attempt WHERE publish_workflow_id = NEW.publish_workflow_id FOR UPDATE;
    PERFORM 1 FROM version WHERE id = NEW.version_id AND tenant_id = NEW.tenant_id FOR UPDATE;
    SELECT * INTO STRICT operation FROM game_design_publication_operation WHERE publish_workflow_id = NEW.publish_workflow_id;
    IF attempt.status <> 'PENDING' OR operation.outcome <> 'PENDING'
        OR operation.tenant_id <> NEW.tenant_id OR operation.version_id <> NEW.version_id
        OR attempt.request_digest IS DISTINCT FROM operation.selection_digest
        OR NEW.attestation_schema_version NOT IN ('v2', 'v3')
        OR convert_to(NEW.world_published_start_location_evidence_json, 'UTF8')
            IS DISTINCT FROM published_world_selector_frame(operation.request_bytes, 2) THEN
        RAISE EXCEPTION 'selected publication is absent, sealed, or changed' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION publication_release_content(release published_release_bundle) RETURNS BYTEA AS $$
DECLARE result BYTEA; entry BYTEA; item JSONB;
BEGIN
    IF release.script_only OR release.script_patch_version IS NOT NULL
        OR release.attestation_schema_version NOT IN ('v2', 'v3') THEN
        RAISE EXCEPTION 'terminal publication requires complete full-version selected content' USING ERRCODE = 'check_violation';
    END IF;
    result := publication_content_text('game-design-published-release-bundle/v1')
        || publication_content_text(release.canonical_tenant_id::TEXT)
        || publication_content_text(release.canonical_version_id::TEXT)
        || publication_content_text(release.published_release_bundle_ref)
        || publication_content_text(release.version_number::TEXT)
        || publication_content_text(release.attestation_schema_version)
        || publication_content_text(release.publish_workflow_id)
        || publication_content_text(release.manifest_hash)
        || publication_content_text(release.manifest_schema_version::TEXT)
        || publication_content_text(jsonb_array_length(release.artifact_digests_json::JSONB)::TEXT);
    FOR item IN SELECT * FROM jsonb_array_elements(release.artifact_digests_json::JSONB) LOOP
        entry := publication_content_text(item->>'usageKey') || publication_content_text(item->>'artifactKind')
            || publication_content_text(item->>'immutableObjectKey') || publication_content_text(item->>'contentDigest')
            || publication_content_text(item->>'contentType') || publication_content_text(item->>'artifactSchemaVersion');
        result := result || publication_content_frame(entry);
    END LOOP;
    result := result || publication_content_strings(release.required_manifest_asset_keys_json::JSONB)
        || publication_content_text(jsonb_array_length(release.participant_digests_json::JSONB)::TEXT);
    FOR item IN SELECT * FROM jsonb_array_elements(release.participant_digests_json::JSONB) LOOP
        entry := publication_content_text(item->>'participantKey') || publication_content_text(item->>'scopeValue')
            || publication_content_optional(item->>'baseVersionId') || publication_content_text(item->>'appliedCommitId')
            || publication_content_text(item->>'contentDigest') || publication_content_text(item->>'digestSchemaVersion')
            || publication_content_optional(item->>'abilitySchemaDigest') || publication_content_optional(item->>'errorCode')
            || publication_content_optional(item->>'errorMessage');
        result := result || publication_content_frame(entry);
    END LOOP;
    RETURN result || publication_content_strings(release.command_definitions_json::JSONB)
        || publication_content_text(release.generation_config_revision)
        || publication_content_text(release.world_published_start_location_evidence_json)
        || publication_content_text('false') || publication_content_optional(NULL);
END;
$$ LANGUAGE plpgsql STABLE STRICT;
-- [jooq ignore stop]
